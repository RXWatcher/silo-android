# Subtitle Artifact Identity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Android TV and mobile select the exact Protocol V3 subtitle artifact requested during playback, even when multiple tracks share the label `Server subtitle`.

**Architecture:** Encode the combined server subtitle index into an internal Media3 track ID, prefer that ID over descriptive metadata, and rebuild replanned subtitle choices with replacement semantics so stale planned artifacts are not retained. TV consumes the stable Media3 ID; mobile adopts the new planned artifact and selected ordinal as one pure state transition.

**Tech Stack:** Kotlin 2.1, Media3, Compose ViewModels, Kotlin test/JUnit, Gradle.

## Global Constraints

- Do not modify SiloCast.
- Do not modify observability, GlitchTip, or Sentry.
- Do not modify server code or CI configuration.
- Do not trigger remote builds.
- Preserve subtitle appearance, synchronization, timeline-offset, Off-selection, and embedded-bitmap behavior.

---

### Task 1: Stable Media3 subtitle artifact identity

**Files:**
- Modify: `shared/src/commonMain/kotlin/org/siloserver/silo/model/playback/PlaybackSubtitleChoices.kt`
- Modify: `shared/src/commonTest/kotlin/org/siloserver/silo/model/playback/PlaybackSubtitleChoicesTest.kt`
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/SubtitleManager.kt`
- Modify: `android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/SubtitleManagerTrackSelectionTest.kt`

**Interfaces:**
- Produces: `subtitleArtifactTrackId(serverIndex: Int): String`
- Produces: `subtitleArtifactServerIndex(trackId: String?): Int?`
- Consumes: `PlayerSubtitleInfo.index`
- Produces: `MediaItem.SubtitleConfiguration.id` carrying the stable identity

- [ ] **Step 1: Write failing shared identity tests**

Add tests proving the ID round-trips, rejects malformed IDs, and cannot alias two server indexes:

```kotlin
@Test
fun subtitleArtifactTrackIdentityRoundTripsCombinedServerIndex() {
    assertEquals("silo-subtitle:3", subtitleArtifactTrackId(3))
    assertEquals(3, subtitleArtifactServerIndex("silo-subtitle:3"))
    assertEquals(4, subtitleArtifactServerIndex("silo-subtitle:4"))
    assertEquals(null, subtitleArtifactServerIndex("Server subtitle"))
    assertEquals(null, subtitleArtifactServerIndex("silo-subtitle:not-a-number"))
}
```

- [ ] **Step 2: Write failing Android shared selection tests**

Add one test asserting `buildSubtitleConfigurations` assigns different IDs to
two otherwise identical artifacts, and one test asserting metadata selection
chooses ID 4 instead of the first ID 3:

```kotlin
@Test
fun serverArtifactConfigurationsCarryStableCombinedIndexes() {
    val configurations = SubtitleManager().buildSubtitleConfigurations(
        listOf(
            PlayerSubtitleInfo(3, "en", "webvtt", "Server subtitle", "server_artifact", true, "/3.vtt"),
            PlayerSubtitleInfo(4, "en", "webvtt", "Server subtitle", "server_artifact", false, "/4.vtt"),
        ),
        "https://silo.example",
    )

    assertEquals(listOf("silo-subtitle:3", "silo-subtitle:4"), configurations.map { it.id })
}

@Test
fun stableArtifactIdentityWinsOverDuplicateRuntimeLabel() {
    val forced = TrackGroup(subtitle("Server subtitle", "en", id = "silo-subtitle:3"))
    val sdh = TrackGroup(subtitle("Server subtitle", "en", id = "silo-subtitle:4"))
    val tracks = Tracks(
        listOf(
            Tracks.Group(forced, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false)),
            Tracks.Group(sdh, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false)),
        ),
    )

    val selection = resolveSubtitleSelection(
        tracks,
        PlayerSubtitleInfo(4, "en", "webvtt", "Server subtitle", "server_artifact", false, "/4.vtt"),
    )

    assertSame(sdh, selection?.mediaTrackGroup)
}
```

- [ ] **Step 3: Run the focused tests and verify RED**

Run:

```bash
./gradlew :shared:jvmTest --tests '*PlaybackSubtitleChoicesTest*' --no-parallel
./gradlew :android-shared:testDebugUnitTest --tests '*SubtitleManagerTrackSelectionTest*' --no-parallel
```

Expected: FAIL because the identity helpers do not exist, configurations do
not set IDs, and selection still returns the first duplicate label.

- [ ] **Step 4: Implement the minimal stable-ID path**

In `PlaybackSubtitleChoices.kt`, add:

```kotlin
private const val SUBTITLE_ARTIFACT_TRACK_ID_PREFIX = "silo-subtitle:"

fun subtitleArtifactTrackId(serverIndex: Int): String =
    "$SUBTITLE_ARTIFACT_TRACK_ID_PREFIX$serverIndex"

fun subtitleArtifactServerIndex(trackId: String?): Int? =
    trackId
        ?.takeIf { it.startsWith(SUBTITLE_ARTIFACT_TRACK_ID_PREFIX) }
        ?.removePrefix(SUBTITLE_ARTIFACT_TRACK_ID_PREFIX)
        ?.toIntOrNull()
        ?.takeIf { it >= 0 }
```

In `SubtitleManager.buildSubtitleConfigurations`, set:

```kotlin
.setId(subtitleArtifactTrackId(subtitle.index))
```

Add `trackId` to `TextTrackCandidate`. In
`resolveSubtitleSelection(tracks, subtitle)`, prefer an exact stable-ID match.
When the requested row has a nonblank sidecar URL and stable-ID candidates are
present, return `null` after an ID miss instead of selecting another artifact
by the generic label. Preserve metadata fallback for legacy tracks without
stable IDs and decoder-backed embedded bitmap rows.

- [ ] **Step 5: Run focused tests and verify GREEN**

Run the command from Step 3.

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/org/siloserver/silo/model/playback/PlaybackSubtitleChoices.kt \
  shared/src/commonTest/kotlin/org/siloserver/silo/model/playback/PlaybackSubtitleChoicesTest.kt \
  android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/SubtitleManager.kt \
  android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/SubtitleManagerTrackSelectionTest.kt
git commit -m "fix(subtitles): give artifacts stable Media3 identities [skip ci]"
```

### Task 2: TV exact selection and replan replacement

**Files:**
- Modify: `shared/src/commonMain/kotlin/org/siloserver/silo/model/playback/PlaybackSubtitleChoices.kt`
- Modify: `shared/src/commonTest/kotlin/org/siloserver/silo/model/playback/PlaybackSubtitleChoicesTest.kt`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerViewModel.kt`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerScreen.kt`
- Modify: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/player/PlayerTrackEntriesTest.kt`

**Interfaces:**
- Consumes: `subtitleArtifactServerIndex(trackId: String?): Int?`
- Produces: `rebuildReplannedSubtitleChoices(catalogTracks, currentChoices, plannedTracks)`
- Extends: `PlayerTrackEntry.trackId: String?`

- [ ] **Step 1: Write failing replacement-semantics test**

Add a shared test with old server artifact index 3, new artifact index 4,
catalog rows 3/4, and downloaded index 20:

```kotlin
@Test
fun replanReplacesStaleServerArtifactAndKeepsDownloadedRows() {
    val choices = rebuildReplannedSubtitleChoices(
        catalogTracks = listOf(
            SubtitleTrack(index = 0, codec = "srt", language = "ar", external = true),
            SubtitleTrack(index = 0, codec = "srt", language = "da", external = true),
            SubtitleTrack(index = 0, codec = "srt", language = "de", external = true),
            SubtitleTrack(index = 0, codec = "srt", language = "en", external = true, forced = true),
            SubtitleTrack(index = 0, codec = "srt", language = "en", external = true),
        ),
        currentChoices = listOf(
            PlayerSubtitleInfo(3, "en", "webvtt", "Server subtitle", "server_artifact", true, "/3.vtt"),
            PlayerSubtitleInfo(20, "nl", "vtt", "Downloaded", "downloaded", null, "/20.vtt"),
        ),
        plannedTracks = listOf(
            PlayerSubtitleInfo(4, "en", "webvtt", "Server subtitle", "server_artifact", false, "/4.vtt"),
        ),
    )

    assertEquals("", choices.first { it.index == 3 }.url)
    assertEquals("/4.vtt", choices.first { it.index == 4 }.url)
    assertEquals("/20.vtt", choices.first { it.index == 20 }.url)
}
```

- [ ] **Step 2: Write failing TV duplicate-label selection test**

Extend `PlayerTrackEntriesTest` with two `PlayerTrackEntry` rows carrying
`trackId = "silo-subtitle:3"` and `"silo-subtitle:4"`, both labeled
`Server subtitle`. Assert `matchesMountedSubtitle` matches only the row whose
stable index equals the requested `PlayerSubtitleInfo.index`.

- [ ] **Step 3: Run focused tests and verify RED**

Run:

```bash
./gradlew :shared:jvmTest --tests '*PlaybackSubtitleChoicesTest*' --no-parallel
./gradlew :androidTvApp:testDebugUnitTest --tests '*PlayerTrackEntriesTest*' --no-parallel
```

Expected: FAIL because replans still retain old server artifacts and
`PlayerTrackEntry` does not expose the Media3 ID.

- [ ] **Step 4: Implement replacement semantics**

Add:

```kotlin
fun rebuildReplannedSubtitleChoices(
    catalogTracks: List<SubtitleTrack>,
    currentChoices: List<PlayerSubtitleInfo>,
    plannedTracks: List<PlayerSubtitleInfo>,
): List<PlayerSubtitleInfo> {
    val plannedIndexes = plannedTracks.mapTo(mutableSetOf(), PlayerSubtitleInfo::index)
    val downloaded = currentChoices.filter {
        it.source == SUBTITLE_SOURCE_DOWNLOADED && it.index !in plannedIndexes
    }
    return buildPlaybackSubtitleChoices(catalogTracks, plannedTracks + downloaded)
}
```

Replace TV's `plannedSubtitles + preservedSubtitles` composition with this
helper.

- [ ] **Step 5: Implement TV stable-ID extraction and matching**

Add `trackId: String? = null` to `PlayerTrackEntry`, populate it from
`Format.id` in `extractTrackEntries`, and make `matchesMountedSubtitle`:

1. parse `trackId` with `subtitleArtifactServerIndex`;
2. if parsed, return whether it equals `subtitle.index`;
3. ignore the generic `Server subtitle` label during legacy metadata fallback;
4. retain existing label/language/codec fallback for tracks without stable IDs.

- [ ] **Step 6: Run focused tests and verify GREEN**

Run the command from Step 3.

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add shared/src/commonMain/kotlin/org/siloserver/silo/model/playback/PlaybackSubtitleChoices.kt \
  shared/src/commonTest/kotlin/org/siloserver/silo/model/playback/PlaybackSubtitleChoicesTest.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerViewModel.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerScreen.kt \
  androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/player/PlayerTrackEntriesTest.kt
git commit -m "fix(tv): select replanned subtitle by server identity [skip ci]"
```

### Task 3: Mobile subtitle replan adoption

**Files:**
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/MobileSubtitleAutoSelection.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerViewModel.kt`
- Modify: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/player/MobileSubtitleAutoSelectionTest.kt`

**Interfaces:**
- Consumes: `rebuildReplannedSubtitleChoices(...)`
- Produces: `MobileReplannedSubtitles(tracks, selectedOrdinal)`
- Produces: `adoptMobileReplannedSubtitles(...)`

- [ ] **Step 1: Write failing mobile adoption test**

```kotlin
@Test
fun mobileReplanAdoptsNewArtifactAndSelectedOrdinal() {
    val adopted = adoptMobileReplannedSubtitles(
        catalogTracks = listOf(
            catalogSubtitle(index = 0, title = "English Forced", forced = true),
            catalogSubtitle(index = 0, title = "English SDH"),
        ),
        currentTracks = listOf(
            PlayerSubtitleInfo(0, "en", "webvtt", "Server subtitle", "server_artifact", true, "/0.vtt"),
        ),
        plannedTracks = listOf(
            PlayerSubtitleInfo(1, "en", "webvtt", "Server subtitle", "server_artifact", false, "/1.vtt"),
        ),
        selectedServerIndex = 1,
    )

    assertEquals(1, adopted.selectedOrdinal)
    assertEquals("", adopted.tracks[0].url)
    assertEquals("/1.vtt", adopted.tracks[1].url)
}
```

- [ ] **Step 2: Run focused test and verify RED**

Run:

```bash
./gradlew :androidApp:testDebugUnitTest \
  --tests '*MobileSubtitleAutoSelectionTest*' \
  --no-parallel
```

Expected: FAIL because `adoptMobileReplannedSubtitles` does not exist.

- [ ] **Step 3: Implement the pure mobile adoption helper**

Add:

```kotlin
internal data class MobileReplannedSubtitles(
    val tracks: List<PlayerSubtitleInfo>,
    val selectedOrdinal: Int,
)

internal fun adoptMobileReplannedSubtitles(
    catalogTracks: List<SubtitleTrack>,
    currentTracks: List<PlayerSubtitleInfo>,
    plannedTracks: List<PlayerSubtitleInfo>,
    selectedServerIndex: Int?,
): MobileReplannedSubtitles {
    val tracks = rebuildReplannedSubtitleChoices(catalogTracks, currentTracks, plannedTracks)
    val selectedOrdinal = selectedServerIndex
        ?.let { index -> tracks.indexOfFirst { it.index == index } }
        ?.takeIf { it >= 0 }
        ?: -1
    return MobileReplannedSubtitles(tracks, selectedOrdinal)
}
```

- [ ] **Step 4: Adopt the helper result in `PlayerViewModel`**

In the successful V3 replan branch, compute adoption from the selected
version's catalog tracks, current subtitle state, `decision.session.subtitleUrls`,
and `selectedSubtitleTrackIndex`. Set both:

```kotlin
subtitleTracks = adopted.tracks
selectedSubtitleIndex = adopted.selectedOrdinal
```

in the same `_uiState.update` that adopts the replacement stream and timeline.

- [ ] **Step 5: Run focused mobile tests and verify GREEN**

Run the command from Step 2.

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/MobileSubtitleAutoSelection.kt \
  androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerViewModel.kt \
  androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/player/MobileSubtitleAutoSelectionTest.kt
git commit -m "fix(mobile): adopt replanned subtitle artifacts [skip ci]"
```

### Task 4: Regression and build verification

**Files:**
- Modify only if a failing regression exposes a defect in Tasks 1–3.

**Interfaces:**
- Verifies all interfaces produced by Tasks 1–3.

- [ ] **Step 1: Run all Android unit tests**

```bash
./gradlew test --no-parallel
```

Expected: BUILD SUCCESSFUL with all tests passing.

- [ ] **Step 2: Assemble phone and TV debug artifacts**

```bash
./gradlew :androidApp:assembleDebug :androidTvApp:assembleDebug --no-parallel
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Audit the final diff**

```bash
git diff --check upstream/main...HEAD
git status --short
git diff --stat upstream/main...HEAD
git diff --name-only upstream/main...HEAD | rg -i 'silocast|observability|glitchtip|sentry|workflow'
```

Expected: no whitespace errors, a clean worktree, and no newly changed
SiloCast/observability/GlitchTip/Sentry/workflow files from this fix.

- [ ] **Step 4: Record verification without triggering CI**

If any verification-only documentation is necessary, commit it with
`[skip ci]`. Do not push or invoke a remote workflow.
