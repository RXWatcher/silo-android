package org.siloserver.silo.domain.settings

import kotlinx.coroutines.test.runTest
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonElement
import org.siloserver.silo.model.settings.EffectiveSettingValuesResponse
import org.siloserver.silo.model.settings.SettingScopeIdentity
import org.siloserver.silo.model.settings.StoredSettingValue
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.SettingsApi
import org.siloserver.silo.network.api.SettingsCapabilitiesResult
import org.siloserver.silo.repository.SettingsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Servers predating the canonical settings API answer 404 to the whole family.
 * Before the fallback, every profile setting — subtitle language, subtitle
 * mode, forced subtitles, metadata language — was accepted optimistically by
 * the screen, rejected by the server, and rolled back, with no error shown.
 * Confirmed in production: `PUT /api/v1/settings/values/playback.subtitle_language`
 * returning 404 while `PUT /api/v1/profiles/{id}` served the same preference.
 */
class LegacyProfileSettingsFallbackTest {

    private class FakeLegacy(
        var stored: ProfileSettingsController.Snapshot? =
            ProfileSettingsController.Snapshot(subtitleLanguage = "de"),
        var writeSucceeds: Boolean = true,
    ) : LegacyProfileSettings {
        var loads = 0
        val writes = mutableListOf<ProfileSettingsController.Snapshot>()

        override suspend fun load(): ProfileSettingsController.Snapshot? {
            loads++
            return stored
        }

        override suspend fun update(
            subtitleLanguage: String?,
            subtitleMode: String?,
            showForcedSubtitles: Boolean?,
            metadataLanguage: String?,
        ): ProfileSettingsController.Snapshot? {
            if (!writeSucceeds) return null
            val next = (stored ?: ProfileSettingsController.Snapshot()).copy(
                subtitleLanguage = subtitleLanguage ?: stored?.subtitleLanguage.orEmpty(),
                subtitleMode = subtitleMode ?: stored?.subtitleMode ?: "auto",
                showForcedSubtitles = showForcedSubtitles ?: stored?.showForcedSubtitles ?: true,
                metadataLanguage = metadataLanguage ?: stored?.metadataLanguage.orEmpty(),
            )
            stored = next
            writes += next
            return next
        }
    }

    /** Answers 404 to everything, like a pre-contract server. */
    private class NoContractApi : SettingsApi(HttpClient()) {
        var putCalls = 0

        override suspend fun getContractCapabilities(): SettingsCapabilitiesResult =
            SettingsCapabilitiesResult.ServerUpgradeRequired

        override suspend fun getEffectiveValues(
            keys: List<String>,
            libraryIds: List<Int>,
            seriesIds: List<String>,
        ): ApiResult<EffectiveSettingValuesResponse> = notFound()

        override suspend fun putValue(
            key: String,
            scope: SettingScopeIdentity,
            value: JsonElement,
            mutationId: String,
            profileId: String?,
        ): ApiResult<StoredSettingValue> {
            putCalls++
            return notFound()
        }

        override suspend fun deleteValue(
            key: String,
            scope: SettingScopeIdentity,
            profileId: String?,
        ): ApiResult<Unit> {
            putCalls++
            return notFound()
        }

        private fun <T> notFound(): ApiResult<T> =
            ApiResult.Error(code = 404, error = "not_found", message = "no such route")
    }

    private fun controller(api: SettingsApi, legacy: LegacyProfileSettings?) =
        ProfileSettingsController(SettingsRepository(api), legacy)

    @Test
    fun `a subtitle language survives on a server without the settings API`() = runTest {
        val legacy = FakeLegacy()
        val controller = controller(NoContractApi(), legacy)
        controller.load()

        val result = controller.setSubtitleLanguage("nl")

        assertTrue(result.succeeded, "write should land on the legacy path")
        assertEquals("nl", result.snapshot?.subtitleLanguage)
        assertEquals("nl", legacy.writes.single().subtitleLanguage)
    }

    @Test
    fun `the load reads real values rather than leaving the screen on defaults`() = runTest {
        // Returning no snapshot would render defaults, and the next edit would
        // write those defaults over preferences the viewer never changed.
        val legacy = FakeLegacy(ProfileSettingsController.Snapshot(subtitleLanguage = "de"))
        val result = controller(NoContractApi(), legacy).load()

        assertEquals("de", result.snapshot?.subtitleLanguage)
        assertEquals(1, legacy.loads)
    }

    @Test
    fun `every profile setting falls back, not just subtitle language`() = runTest {
        val legacy = FakeLegacy()
        val controller = controller(NoContractApi(), legacy)
        controller.load()

        assertTrue(controller.setSubtitleMode("always").succeeded)
        assertTrue(controller.setShowForcedSubtitles(false).succeeded)
        assertTrue(controller.setMetadataLanguage("fr").succeeded)

        assertEquals("always", legacy.stored?.subtitleMode)
        assertEquals(false, legacy.stored?.showForcedSubtitles)
        assertEquals("fr", legacy.stored?.metadataLanguage)
    }

    @Test
    fun `an unprobed server still tries the contract first and falls back on its 404`() = runTest {
        // No load() call, so the contract has not been probed. The canonical
        // path must still be attempted — assuming the worst would push every
        // modern server onto the legacy columns.
        val api = NoContractApi()
        val legacy = FakeLegacy()

        val result = controller(api, legacy).setSubtitleLanguage("nl")

        assertTrue(api.putCalls > 0, "canonical path should have been tried")
        assertTrue(result.succeeded)
        assertEquals("nl", legacy.writes.single().subtitleLanguage)
    }

    @Test
    fun `the contract is only abandoned once, not re-probed per write`() = runTest {
        val api = NoContractApi()
        val legacy = FakeLegacy()
        val controller = controller(api, legacy)

        controller.setSubtitleLanguage("nl")
        val callsAfterFirst = api.putCalls
        controller.setSubtitleLanguage("fr")

        assertEquals(callsAfterFirst, api.putCalls, "second write should skip the dead route")
        assertEquals(2, legacy.writes.size)
    }

    @Test
    fun `without a fallback the failure is still reported`() = runTest {
        val result = controller(NoContractApi(), legacy = null).setSubtitleLanguage("nl")

        assertFalse(result.succeeded)
    }

    @Test
    fun `a failing legacy write is reported rather than claimed as success`() = runTest {
        val legacy = FakeLegacy(writeSucceeds = false)
        val controller = controller(NoContractApi(), legacy)
        controller.load()

        val result = controller.setSubtitleLanguage("nl")

        assertFalse(result.succeeded)
        assertEquals(null, result.snapshot)
    }
}
