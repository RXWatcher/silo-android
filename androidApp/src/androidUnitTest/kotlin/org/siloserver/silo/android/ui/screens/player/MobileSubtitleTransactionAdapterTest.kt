package org.siloserver.silo.android.ui.screens.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.playback.CommittedSubtitle
import org.siloserver.silo.model.playback.PlaybackSubtitleModeV3
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitleMediaIdentity
import org.siloserver.silo.network.ApiResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MobileSubtitleTransactionAdapterTest {
    @Test
    fun `pre-playback server selection commits without staging`() = runTest {
        val harness = harness(backgroundScope, sessionId = null)

        harness.adapter.select(sidecar(4))
        runCurrent()

        assertEquals(sidecar(4), harness.adapter.snapshot.committedIdentity)
        assertNull(harness.adapter.snapshot.pendingIdentity)
        assertTrue(harness.port.requests.isEmpty())
        assertEquals(listOf(sidecar(4)), harness.persistence.persisted.map { it.identity })
    }

    @Test
    fun `slow older preference write cannot overwrite newer commit`() = runTest {
        val harness = harness(backgroundScope, sessionId = null)
        harness.persistence.suspendFirst = true

        harness.adapter.select(sidecar(4))
        runCurrent()
        harness.persistence.awaitFirstStarted()
        harness.adapter.select(sidecar(5))
        runCurrent()
        assertTrue(harness.persistence.persisted.isEmpty())

        harness.persistence.releaseFirst()
        runCurrent()

        assertEquals(
            listOf(sidecar(4), sidecar(5)),
            harness.persistence.persisted.map { it.identity },
        )
    }

    @Test
    fun `A remains committed while B stages and commits`() = runTest {
        val harness = harness(backgroundScope)

        harness.adapter.select(sidecar(4))
        runCurrent()

        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertEquals(sidecar(4), harness.adapter.snapshot.pendingIdentity)
        assertTrue(harness.adapter.snapshot.subtitleApplying)
        assertTrue(harness.persistence.persisted.isEmpty())

        harness.port.completeStage(candidate("b", 4))
        runCurrent()

        assertEquals(sidecar(4), harness.adapter.snapshot.committedIdentity)
        assertNull(harness.adapter.snapshot.pendingIdentity)
        assertEquals(listOf("b"), harness.port.committed)
        assertEquals(listOf(sidecar(4)), harness.persistence.persisted.map { it.identity })
    }

    @Test
    fun `A to B to C discards B and commits only latest C`() = runTest {
        val harness = harness(backgroundScope)

        harness.adapter.select(sidecar(4))
        runCurrent()
        harness.adapter.select(sidecar(5))
        runCurrent()
        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertEquals(sidecar(5), harness.adapter.snapshot.pendingIdentity)

        harness.port.completeStage(candidate("b", 4))
        runCurrent()
        assertEquals(listOf("b"), harness.port.discarded)
        assertEquals(listOf(4, 5), harness.port.requests.map { it.subtitleTrackIndex })

        harness.port.completeStage(candidate("c", 5))
        runCurrent()
        assertEquals(sidecar(5), harness.adapter.snapshot.committedIdentity)
        assertEquals(listOf("c"), harness.port.committed)
        assertEquals(listOf(sidecar(5)), harness.persistence.persisted.map { it.identity })
    }

    @Test
    fun `subtitle then audio merge into one latest reducer transaction`() = runTest {
        val harness = harness(backgroundScope)

        harness.adapter.select(sidecar(4))
        runCurrent()
        harness.adapter.selectAudio(7)
        harness.port.completeStage(candidate("subtitle-only", 4, selectedAudioIndex = 2))
        runCurrent()

        assertEquals(listOf(2, 7), harness.port.requests.map { it.audioTrackIndex })
        assertEquals(listOf(4, 4), harness.port.requests.map { it.subtitleTrackIndex })
        assertEquals(listOf("subtitle-only"), harness.port.discarded)

        harness.port.completeStage(candidate("combined", 4, selectedAudioIndex = 7))
        runCurrent()

        assertEquals(sidecar(4), harness.adapter.snapshot.committedIdentity)
        assertEquals(7, harness.adapter.snapshot.transition.committed.audioTrackIndex)
        assertEquals(listOf(7), harness.persistence.persisted.map { it.audioTrackIndex })
    }

    @Test
    fun `audio then subtitle merge into one latest reducer transaction`() = runTest {
        val harness = harness(backgroundScope)

        harness.adapter.selectAudio(7)
        runCurrent()
        harness.adapter.select(sidecar(4))
        harness.port.completeStage(candidate("audio-only", 3, selectedAudioIndex = 7))
        runCurrent()

        assertEquals(listOf(7, 7), harness.port.requests.map { it.audioTrackIndex })
        assertEquals(listOf(3, 4), harness.port.requests.map { it.subtitleTrackIndex })
        assertEquals(listOf("audio-only"), harness.port.discarded)

        harness.port.completeStage(candidate("combined", 4, selectedAudioIndex = 7))
        runCurrent()

        assertEquals(sidecar(4), harness.adapter.snapshot.committedIdentity)
        assertEquals(7, harness.adapter.snapshot.transition.committed.audioTrackIndex)
    }

    @Test
    fun `audio change remounts committed downloaded subtitle without sending client index to server`() = runTest {
        val row = downloadedTrack(
            index = 9,
            downloadId = 312,
            url = "https://silo.test/api/v1/stream/s1/subtitles/9.vtt",
        )
        val downloaded = SubtitleIdentity.Downloaded(
            downloadId = 312,
            media = media(
                trackId = "silo-downloaded-subtitle:312",
                language = "en",
                codec = "webvtt",
            ),
        )
        val harness = harness(backgroundScope, tracks = listOf(row))
        harness.adapter.resetContent(
            context(sessionId = "s1", tracks = listOf(row)),
            committedIdentity = downloaded,
        )

        harness.adapter.selectAudio(7)
        runCurrent()

        assertEquals(-1, harness.port.requests.single().subtitleTrackIndex)
        harness.port.completeStage(
            candidate(
                id = "downloaded-audio",
                selectedIndex = null,
                selectedAudioIndex = 7,
                mode = PlaybackSubtitleModeV3.OFF,
                hasSidecar = false,
            ),
        )
        runCurrent()

        assertEquals(downloaded, harness.adapter.snapshot.committedIdentity)
        assertEquals(7, harness.adapter.snapshot.transition.committed.audioTrackIndex)
        assertEquals(downloaded, harness.adapter.snapshot.localMountIdentity)
        assertEquals(
            312,
            harness.committedPlaybacks.single().subtitleTracks.single().downloadId,
        )
        assertTrue(
            harness.committedPlaybacks.single().subtitleTracks.single().url
                .contains("/stream/s-downloaded-audio/"),
        )
        val persistedBeforeRestoreConfirmation = harness.persistence.persisted.size
        harness.adapter.reportMountedSelection(
            identity = downloaded,
            selected = true,
            snapshotKey = "downloaded-restored",
            settled = true,
        )
        runCurrent()
        assertNull(harness.adapter.snapshot.localMountIdentity)
        assertEquals(downloaded, harness.adapter.snapshot.committedIdentity)
        assertEquals(persistedBeforeRestoreConfirmation, harness.persistence.persisted.size)
    }

    @Test
    fun `audio change remounts committed local Media3 subtitle with server subtitles off`() = runTest {
        val row = PlayerSubtitleInfo(
            index = 6,
            language = "fr",
            codec = "vtt",
            label = "Legacy local French",
            source = "downloaded",
            forced = false,
            url = "https://silo.test/api/v1/stream/s1/subtitles/6.vtt",
            mediaTrackId = "decoder-text-6",
        )
        val local = SubtitleIdentity.LocalMedia3(
            media(
                trackId = "decoder-text-6",
                label = "Legacy local French",
                language = "fr",
                codec = "webvtt",
            ),
        )
        val harness = harness(backgroundScope, tracks = listOf(row))
        harness.adapter.resetContent(
            context(sessionId = "s1", tracks = listOf(row)),
            committedIdentity = local,
        )

        harness.adapter.selectAudio(7)
        runCurrent()

        assertEquals(-1, harness.port.requests.single().subtitleTrackIndex)
        harness.port.completeStage(
            candidate(
                id = "local-audio",
                selectedIndex = null,
                selectedAudioIndex = 7,
                mode = PlaybackSubtitleModeV3.OFF,
                hasSidecar = false,
            ),
        )
        runCurrent()

        assertEquals(local, harness.adapter.snapshot.committedIdentity)
        assertEquals(local, harness.adapter.snapshot.localMountIdentity)
        assertEquals("decoder-text-6", harness.committedPlaybacks.single().subtitleTracks.single().mediaTrackId)
        assertTrue(
            harness.committedPlaybacks.single().subtitleTracks.single().url
                .contains("/stream/s-local-audio/"),
        )
        harness.adapter.reportMountedSelection(
            identity = local,
            selected = false,
            snapshotKey = "ready-local-restore-miss",
            settled = true,
        )
        runCurrent()
        assertEquals(local, harness.adapter.snapshot.committedIdentity)
        assertNull(harness.adapter.snapshot.localMountIdentity)
        assertTrue(harness.adapter.snapshot.failureMessage?.contains("mount", ignoreCase = true) == true)
    }

    @Test
    fun `post-adoption local restore timeout keeps committed preference`() = runTest {
        val row = downloadedTrack(
            index = 9,
            downloadId = 312,
            url = "https://silo.test/api/v1/stream/s1/subtitles/9.vtt",
        )
        val downloaded = SubtitleIdentity.Downloaded(
            downloadId = 312,
            media = media(
                trackId = "silo-downloaded-subtitle:312",
                language = "en",
                codec = "webvtt",
            ),
        )
        val harness = harness(backgroundScope, tracks = listOf(row))
        harness.adapter.resetContent(
            context(sessionId = "s1", tracks = listOf(row)),
            committedIdentity = downloaded,
        )
        harness.adapter.selectAudio(7)
        runCurrent()
        harness.port.completeStage(
            candidate(
                id = "restore-timeout",
                selectedIndex = null,
                selectedAudioIndex = 7,
                mode = PlaybackSubtitleModeV3.OFF,
                hasSidecar = false,
            ),
        )
        runCurrent()
        assertEquals(downloaded, harness.adapter.snapshot.localMountIdentity)

        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(downloaded, harness.adapter.snapshot.committedIdentity)
        assertNull(harness.adapter.snapshot.localMountIdentity)
        assertTrue(harness.adapter.snapshot.failureMessage?.contains("mount", ignoreCase = true) == true)
    }

    @Test
    fun `A to Off keeps A mounted until Off candidate commits`() = runTest {
        val harness = harness(backgroundScope)

        harness.adapter.select(SubtitleIdentity.Off)
        runCurrent()
        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertEquals(SubtitleIdentity.Off, harness.adapter.snapshot.pendingIdentity)
        assertEquals(-1, harness.port.requests.single().subtitleTrackIndex)

        harness.port.completeStage(
            candidate(
                id = "off",
                selectedIndex = null,
                mode = PlaybackSubtitleModeV3.OFF,
            ),
        )
        runCurrent()

        assertEquals(SubtitleIdentity.Off, harness.adapter.snapshot.committedIdentity)
        assertEquals(listOf(SubtitleIdentity.Off), harness.persistence.persisted.map { it.identity })
    }

    @Test
    fun `missing sidecar and network failure retain committed selection and preference`() = runTest {
        val harness = harness(backgroundScope)

        harness.adapter.select(sidecar(4))
        runCurrent()
        harness.port.completeStage(
            candidate(
                id = "missing-sidecar",
                selectedIndex = 4,
                mode = PlaybackSubtitleModeV3.RENDER,
                hasSidecar = false,
            ),
        )
        runCurrent()

        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertNull(harness.adapter.snapshot.pendingIdentity)
        assertEquals(listOf("missing-sidecar"), harness.port.discarded)
        assertTrue(harness.persistence.persisted.isEmpty())
        assertTrue(harness.adapter.snapshot.failureMessage?.contains("sidecar", ignoreCase = true) == true)

        harness.adapter.select(sidecar(5))
        runCurrent()
        harness.port.failStage(ApiResult.NetworkError(IllegalStateException("offline")))
        runCurrent()

        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertNull(harness.adapter.snapshot.pendingIdentity)
        assertTrue(harness.persistence.persisted.isEmpty())
    }

    @Test
    fun `burn-in candidate commits without a sidecar`() = runTest {
        val harness = harness(backgroundScope)
        val burnIn = SubtitleIdentity.ServerBurnIn(8)

        harness.adapter.select(burnIn)
        runCurrent()
        harness.port.completeStage(
            candidate(
                id = "burn-in",
                selectedIndex = 8,
                mode = PlaybackSubtitleModeV3.BURN_IN,
                hasSidecar = false,
            ),
        )
        runCurrent()

        assertEquals(burnIn, harness.adapter.snapshot.committedIdentity)
        assertEquals(listOf(burnIn), harness.persistence.persisted.map { it.identity })
    }

    @Test
    fun `downloaded and embedded choices persist only after mounted resolver confirms`() = runTest {
        val harness = harness(backgroundScope)
        val downloaded = SubtitleIdentity.Downloaded(
            downloadId = 312,
            media = media(
                trackId = "silo-downloaded-subtitle:312",
                label = "English",
                language = "en",
                codec = "webvtt",
            ),
        )
        val embedded = SubtitleIdentity.Embedded(
            serverIndex = 7,
            media = media(
                trackId = "decoder-pgs-7",
                label = "English Forced",
                language = "en",
                codec = "pgs",
                forced = true,
            ),
        )

        harness.adapter.select(downloaded)
        runCurrent()
        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertEquals(downloaded, harness.adapter.snapshot.pendingIdentity)
        assertEquals(downloaded, harness.adapter.snapshot.localMountIdentity)
        assertTrue(harness.persistence.persisted.isEmpty())
        harness.adapter.reportMountedSelection(
            identity = downloaded,
            selected = true,
            snapshotKey = "downloaded-mounted",
        )
        runCurrent()
        assertEquals(downloaded, harness.adapter.snapshot.committedIdentity)

        harness.adapter.select(embedded)
        runCurrent()
        assertEquals(downloaded, harness.adapter.snapshot.committedIdentity)
        assertEquals(embedded, harness.adapter.snapshot.localMountIdentity)
        harness.adapter.reportMountedSelection(
            identity = embedded,
            selected = true,
            snapshotKey = "embedded-mounted",
        )
        runCurrent()
        assertEquals(embedded, harness.adapter.snapshot.committedIdentity)
        assertTrue(harness.port.requests.isEmpty())
        assertEquals(
            listOf(downloaded, embedded),
            harness.persistence.persisted.map { it.identity },
        )
    }

    @Test
    fun `settled local mount miss rolls back immediately without persistence`() = runTest {
        val harness = harness(backgroundScope)
        val local = SubtitleIdentity.LocalMedia3(
            media(label = "English", language = "en", codec = "webvtt"),
        )

        harness.adapter.select(local)
        harness.adapter.reportMountedSelection(
            identity = local,
            selected = false,
            snapshotKey = "ready-track-catalog",
            settled = true,
        )
        runCurrent()

        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertNull(harness.adapter.snapshot.pendingIdentity)
        assertTrue(harness.persistence.persisted.isEmpty())
        assertTrue(harness.adapter.snapshot.failureMessage?.contains("mount", ignoreCase = true) == true)
    }

    @Test
    fun `repeated and empty local mount snapshots do not exhaust retry bound`() = runTest {
        val harness = harness(backgroundScope)
        val local = SubtitleIdentity.LocalMedia3(
            media(label = "English", language = "en", codec = "webvtt"),
        )

        harness.adapter.select(local)
        repeat(5) {
            harness.adapter.reportMountedSelection(
                identity = local,
                selected = false,
                snapshotKey = if (it == 0) null else "same-mounted-catalog",
            )
        }

        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertEquals(local, harness.adapter.snapshot.pendingIdentity)
        assertEquals(local, harness.adapter.snapshot.localMountIdentity)
        assertTrue(harness.persistence.persisted.isEmpty())
    }

    @Test
    fun `local mount rolls back after bounded timeout when tracks never settle`() = runTest {
        val harness = harness(backgroundScope)
        val local = SubtitleIdentity.LocalMedia3(
            media(label = "English", language = "en", codec = "webvtt"),
        )

        harness.adapter.select(local)
        repeat(5) {
            harness.adapter.reportMountedSelection(
                identity = local,
                selected = false,
                snapshotKey = if (it == 0) null else "same-transient-catalog",
            )
        }
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(local, harness.adapter.snapshot.pendingIdentity)

        advanceTimeBy(1)
        runCurrent()

        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertNull(harness.adapter.snapshot.pendingIdentity)
        assertTrue(harness.persistence.persisted.isEmpty())
        assertTrue(harness.adapter.snapshot.failureMessage?.contains("mount", ignoreCase = true) == true)
    }

    @Test
    fun `committed session replacement rebases downloaded rows to real session identity`() = runTest {
        val downloaded = downloadedTrack(
            index = 9,
            downloadId = 312,
            url = "https://silo.test/api/v1/stream/s1/subtitles/9.vtt?token=s1",
        )
        val harness = harness(backgroundScope, tracks = listOf(downloaded))
        harness.adapter.select(sidecar(4))
        runCurrent()

        harness.port.completeStage(
            candidate(
                id = "b",
                selectedIndex = 4,
                sessionId = "s2",
                tracks = listOf(serverTrack(4, "/stream/s2/subtitles/4.vtt")),
            ),
        )
        runCurrent()

        val committed = harness.committedPlaybacks.single()
        assertEquals("s2", committed.sessionId)
        assertEquals(
            "https://silo.test/api/v1/stream/s2/subtitles/9.vtt?token=s1",
            committed.subtitleTracks.single { it.downloadId == 312 }.url,
        )
    }

    @Test
    fun `content file version and session reset invalidates staged response`() = runTest {
        val harness = harness(backgroundScope)
        harness.adapter.select(sidecar(4))
        runCurrent()

        harness.adapter.resetContent(
            context(
                contentId = "content-2",
                mediaFileId = 22,
                versionId = "version-2",
                sessionId = "s9",
            ),
            committedIdentity = SubtitleIdentity.Off,
        )
        harness.port.completeStage(candidate("old", 4))
        runCurrent()

        assertEquals(SubtitleIdentity.Off, harness.adapter.snapshot.committedIdentity)
        assertEquals(listOf("old"), harness.port.discarded)
        assertTrue(harness.persistence.persisted.isEmpty())
    }

    @Test
    fun `new selection during suspended commit is applied after committed base without stale overwrite`() = runTest {
        val harness = harness(backgroundScope)
        harness.port.suspendCommits = true
        harness.adapter.select(sidecar(4))
        runCurrent()
        harness.port.completeStage(candidate("b", 4, sessionId = "s2"))
        runCurrent()
        assertEquals(listOf("b"), harness.port.commitStarted)

        harness.adapter.select(sidecar(5))
        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertEquals(sidecar(5), harness.adapter.snapshot.pendingIdentity)

        harness.port.completeCommit("b")
        runCurrent()

        assertEquals(sidecar(4), harness.adapter.snapshot.committedIdentity)
        assertEquals(sidecar(5), harness.adapter.snapshot.pendingIdentity)
        assertEquals(listOf(4, 5), harness.port.requests.map { it.subtitleTrackIndex })
        assertEquals(listOf("s2"), harness.committedPlaybacks.map { it.sessionId })
        assertTrue(harness.persistence.persisted.isEmpty())

        harness.port.completeStage(candidate("c", 5, sessionId = "s3"))
        harness.port.completeCommit("c")
        runCurrent()
        assertEquals(sidecar(5), harness.adapter.snapshot.committedIdentity)
        assertEquals(listOf(sidecar(5)), harness.persistence.persisted.map { it.identity })
    }

    @Test
    fun `reset during suspended commit prevents old playback adoption and persistence`() = runTest {
        val harness = harness(backgroundScope)
        harness.port.suspendCommits = true
        harness.adapter.select(sidecar(4))
        runCurrent()
        harness.port.completeStage(candidate("b", 4, sessionId = "s2"))
        runCurrent()

        harness.adapter.resetContent(
            context(contentId = "content-2", mediaFileId = 22, versionId = "v2", sessionId = "s9"),
            committedIdentity = SubtitleIdentity.Off,
        )
        harness.port.completeCommit("b")
        runCurrent()

        assertEquals(SubtitleIdentity.Off, harness.adapter.snapshot.committedIdentity)
        assertTrue(harness.committedPlaybacks.isEmpty())
        assertTrue(harness.persistence.persisted.isEmpty())
        assertEquals(listOf("s2"), harness.port.abandoned)
    }

    @Test
    fun `new selection stays queued until suspended playback adoption completes`() = runTest {
        val adoption = AdoptionControl(suspendAdoption = true)
        val harness = harness(backgroundScope, adoption = adoption)
        harness.adapter.select(sidecar(4))
        runCurrent()
        harness.port.completeStage(candidate("b", 4, sessionId = "s2"))
        runCurrent()
        assertEquals(1, adoption.started)

        harness.adapter.select(sidecar(5))
        runCurrent()

        assertEquals(
            listOf(4),
            harness.port.requests.map { it.subtitleTrackIndex },
            "A newer intent must not stage from the manager-committed base before lifecycle adoption finishes.",
        )
        assertEquals(sidecar(5), harness.adapter.snapshot.pendingIdentity)
        assertTrue(harness.persistence.persisted.isEmpty())
        assertTrue(harness.port.abandoned.isEmpty())

        adoption.complete()
        runCurrent()

        assertEquals(listOf(4, 5), harness.port.requests.map { it.subtitleTrackIndex })
        assertEquals(sidecar(4), harness.adapter.snapshot.committedIdentity)
        assertEquals(sidecar(5), harness.adapter.snapshot.pendingIdentity)
    }

    @Test
    fun `audio change during adoption waits and stages from adopted subtitle`() = runTest {
        val adoption = AdoptionControl(suspendAdoption = true)
        val harness = harness(backgroundScope, adoption = adoption)
        harness.adapter.select(sidecar(4))
        runCurrent()
        harness.port.completeStage(candidate("subtitle", 4, selectedAudioIndex = 2, sessionId = "s2"))
        runCurrent()

        harness.adapter.selectAudio(7)
        runCurrent()
        assertEquals(listOf(4), harness.port.requests.map { it.subtitleTrackIndex })
        assertEquals(sidecar(4), harness.adapter.snapshot.pendingIdentity)

        adoption.complete()
        runCurrent()

        assertEquals(listOf(4, 4), harness.port.requests.map { it.subtitleTrackIndex })
        assertEquals(listOf(2, 7), harness.port.requests.map { it.audioTrackIndex })
        harness.port.completeStage(candidate("audio", 4, selectedAudioIndex = 7, sessionId = "s3"))
        runCurrent()
        adoption.complete()
        runCurrent()

        assertEquals(sidecar(4), harness.adapter.snapshot.committedIdentity)
        assertEquals(7, harness.adapter.snapshot.transition.committed.audioTrackIndex)
    }

    @Test
    fun `reset during suspended playback adoption invalidates stale callback and persistence`() = runTest {
        val adoption = AdoptionControl(suspendAdoption = true)
        val harness = harness(backgroundScope, adoption = adoption)
        harness.adapter.select(sidecar(4))
        runCurrent()
        harness.port.completeStage(candidate("b", 4, sessionId = "s2"))
        runCurrent()
        assertEquals(1, adoption.started)

        harness.adapter.resetContent(
            context(contentId = "content-2", mediaFileId = 22, versionId = "v2", sessionId = "s9"),
            committedIdentity = SubtitleIdentity.Off,
        )
        adoption.complete()
        runCurrent()

        assertEquals(SubtitleIdentity.Off, harness.adapter.snapshot.committedIdentity)
        assertTrue(harness.committedPlaybacks.isEmpty())
        assertTrue(harness.persistence.persisted.isEmpty())
    }

    @Test
    fun `playback adoption exception is contained and worker remains available`() = runTest {
        val adoption = AdoptionControl(
            failure = IllegalStateException("lifecycle adoption failed"),
        )
        val harness = harness(backgroundScope, adoption = adoption)
        harness.adapter.select(sidecar(4))
        runCurrent()
        harness.port.completeStage(candidate("b", 4, sessionId = "s2"))
        runCurrent()

        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertNull(harness.adapter.snapshot.pendingIdentity)
        assertTrue(
            harness.adapter.snapshot.failureMessage
                ?.contains("adoption", ignoreCase = true) == true,
        )
        assertTrue(harness.persistence.persisted.isEmpty())

        harness.adapter.select(sidecar(5))
        runCurrent()
        assertEquals(listOf(4, 5), harness.port.requests.map { it.subtitleTrackIndex })
    }

    @Test
    fun `refresh owner rejects stale response after intent and session changes`() = runTest {
        val harness = harness(backgroundScope)
        val first = harness.adapter.beginRefresh()
        assertTrue(harness.adapter.ownsRefresh(first))

        harness.adapter.select(sidecar(4))
        runCurrent()
        assertFalse(harness.adapter.ownsRefresh(first))

        val second = harness.adapter.beginRefresh()
        assertTrue(harness.adapter.ownsRefresh(second))
        harness.adapter.replaceSession("s2")
        assertFalse(harness.adapter.ownsRefresh(second))
    }

    @Test
    fun `auto selection enters reducer only for current refresh owner`() = runTest {
        val harness = harness(backgroundScope)
        val stale = harness.adapter.beginRefresh()
        val current = harness.adapter.beginRefresh()
        val downloaded = SubtitleIdentity.Downloaded(
            downloadId = 91,
            media = media(trackId = "silo-downloaded-subtitle:91", language = "en", codec = "webvtt"),
        )

        assertFalse(harness.adapter.selectFromRefresh(stale, downloaded))
        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertTrue(harness.adapter.selectFromRefresh(current, downloaded))
        runCurrent()

        assertEquals(sidecar(3), harness.adapter.snapshot.committedIdentity)
        assertEquals(downloaded, harness.adapter.snapshot.localMountIdentity)
        assertTrue(harness.persistence.persisted.isEmpty())
        harness.adapter.reportMountedSelection(
            identity = downloaded,
            selected = true,
            snapshotKey = "auto-downloaded-mounted",
        )
        runCurrent()

        assertEquals(downloaded, harness.adapter.snapshot.committedIdentity)
        assertEquals(listOf(downloaded), harness.persistence.persisted.map { it.identity })
    }

    private fun harness(
        scope: CoroutineScope,
        sessionId: String? = "s1",
        tracks: List<PlayerSubtitleInfo> = emptyList(),
        adoption: AdoptionControl = AdoptionControl(),
    ): Harness {
        val port = FakeStagedPort()
        val persistence = RecordingPersistence()
        val committedPlaybacks = mutableListOf<MobileSubtitleCommittedPlayback>()
        val adapter = MobileSubtitleTransactionAdapter(
            scope = scope,
            stagedPort = port,
            persistencePort = persistence,
            onCommittedPlayback = { adoptionRequest ->
                adoption.started += 1
                if (adoption.suspendAdoption) adoption.completions.receive()
                adoption.failure?.let { throw it }
                if (!adoptionRequest.isCurrent()) {
                    MobileSubtitleAdoptionResult.Superseded
                } else {
                    committedPlaybacks += adoptionRequest.playback
                    MobileSubtitleAdoptionResult.Adopted
                }
            },
        )
        adapter.resetContent(
            context(sessionId = sessionId, tracks = tracks),
            committedIdentity = sidecar(3),
        )
        return Harness(adapter, port, persistence, committedPlaybacks)
    }

    private class AdoptionControl(
        val suspendAdoption: Boolean = false,
        val failure: Throwable? = null,
    ) {
        var started: Int = 0
        val completions = Channel<Unit>(Channel.UNLIMITED)

        suspend fun complete() {
            completions.send(Unit)
        }
    }

    private fun context(
        contentId: String = "content-1",
        mediaFileId: Int = 11,
        versionId: String = "version-1",
        sessionId: String? = "s1",
        tracks: List<PlayerSubtitleInfo> = emptyList(),
    ): MobileSubtitlePlaybackContext = MobileSubtitlePlaybackContext(
        contentId = contentId,
        mediaFileId = mediaFileId,
        versionId = versionId,
        sessionId = sessionId,
        positionSeconds = 42.0,
        audioTrackIndex = 2,
        qualityPreference = "auto",
        subtitleTracks = tracks,
    )

    private fun candidate(
        id: String,
        selectedIndex: Int?,
        selectedAudioIndex: Int? = null,
        mode: PlaybackSubtitleModeV3 = PlaybackSubtitleModeV3.RENDER,
        hasSidecar: Boolean = mode == PlaybackSubtitleModeV3.RENDER ||
            mode == PlaybackSubtitleModeV3.CONVERT,
        sessionId: String = "s-$id",
        tracks: List<PlayerSubtitleInfo> = emptyList(),
    ): MobileStagedSubtitleCandidate = MobileStagedSubtitleCandidate(
        id = id,
        sessionId = sessionId,
        selectedSubtitleIndex = selectedIndex,
        selectedAudioIndex = selectedAudioIndex,
        subtitleMode = mode,
        hasSidecar = hasSidecar,
        subtitleTracks = tracks,
    )

    private fun sidecar(index: Int): SubtitleIdentity = SubtitleIdentity.ServerSidecar(index)

    private fun media(
        trackId: String? = null,
        label: String? = null,
        language: String? = null,
        codec: String? = null,
        forced: Boolean? = null,
    ): SubtitleMediaIdentity = SubtitleMediaIdentity(
        trackId = trackId,
        label = label,
        language = language,
        codecFamily = codec,
        forced = forced,
        hearingImpaired = false,
    )

    private fun serverTrack(index: Int, url: String): PlayerSubtitleInfo = PlayerSubtitleInfo(
        index = index,
        language = "en",
        codec = "srt",
        label = "Server subtitle",
        source = "server_artifact",
        forced = false,
        url = url,
    )

    private fun downloadedTrack(index: Int, downloadId: Int, url: String): PlayerSubtitleInfo =
        PlayerSubtitleInfo(
            index = index,
            language = "en",
            codec = "vtt",
            label = "English",
            source = "downloaded",
            forced = false,
            url = url,
            downloadId = downloadId,
        )

    private data class Harness(
        val adapter: MobileSubtitleTransactionAdapter,
        val port: FakeStagedPort,
        val persistence: RecordingPersistence,
        val committedPlaybacks: MutableList<MobileSubtitleCommittedPlayback>,
    )

    private class FakeStagedPort : MobileSubtitleStagedReplanPort {
        val requests = mutableListOf<MobileSubtitleStageRequest>()
        val committed = mutableListOf<String>()
        val commitStarted = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        val abandoned = mutableListOf<String>()
        var suspendCommits = false
        private val stageResults = Channel<ApiResult<MobileStagedSubtitleCandidate>>(Channel.UNLIMITED)
        private val commitResults = Channel<String>(Channel.UNLIMITED)

        override suspend fun stage(request: MobileSubtitleStageRequest): ApiResult<MobileStagedSubtitleCandidate> {
            requests += request
            return stageResults.receive()
        }

        override suspend fun commit(
            candidate: MobileStagedSubtitleCandidate,
        ): ApiResult<MobileSubtitleCommittedPlayback> {
            commitStarted += candidate.id
            if (suspendCommits) {
                val committedId = commitResults.receive()
                check(committedId == candidate.id)
            }
            committed += candidate.id
            return ApiResult.Success(
                MobileSubtitleCommittedPlayback(
                    sessionId = candidate.sessionId,
                    subtitleTracks = candidate.subtitleTracks,
                ),
            )
        }

        override suspend fun discard(candidate: MobileStagedSubtitleCandidate) {
            discarded += candidate.id
        }

        override suspend fun abandonCommitted(playback: MobileSubtitleCommittedPlayback) {
            abandoned += playback.sessionId
        }

        suspend fun completeStage(candidate: MobileStagedSubtitleCandidate) {
            stageResults.send(ApiResult.Success(candidate))
        }

        suspend fun failStage(result: ApiResult<Nothing>) {
            stageResults.send(result)
        }

        suspend fun completeCommit(id: String) {
            commitResults.send(id)
        }
    }

    private class RecordingPersistence : MobileSubtitlePersistencePort {
        val persisted = mutableListOf<CommittedSubtitle>()
        var suspendFirst = false
        private var calls = 0
        private val firstStarted = Channel<Unit>(Channel.CONFLATED)
        private val firstRelease = Channel<Unit>(Channel.CONFLATED)

        override suspend fun persist(
            committed: CommittedSubtitle,
            context: MobileSubtitlePlaybackContext,
        ) {
            val call = calls++
            if (suspendFirst && call == 0) {
                firstStarted.send(Unit)
                firstRelease.receive()
            }
            persisted += committed
        }

        suspend fun awaitFirstStarted() {
            firstStarted.receive()
        }

        suspend fun releaseFirst() {
            firstRelease.send(Unit)
        }
    }
}
