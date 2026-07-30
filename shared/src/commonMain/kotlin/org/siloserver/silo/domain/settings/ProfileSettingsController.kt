package org.siloserver.silo.domain.settings

import org.siloserver.silo.model.settings.EffectiveSettingValue
import org.siloserver.silo.model.settings.SettingKeys
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.SettingsCapabilitiesResult
import org.siloserver.silo.repository.SettingsRepository
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The profile-scoped half of the settings screens, shared by the phone and TV
 * apps.
 *
 * These preferences used to ride `PUT /profiles/{id}` as named columns. They
 * are canonical settings now: reads come from the batched effective endpoint
 * (so a value set from another device, or narrowed by policy, is what the
 * screen shows) and writes address `scope=profile` explicitly. Android no
 * longer depends on the profile endpoint accepting `subtitle_language`,
 * `subtitle_mode`, `show_forced_subtitles` or `preferred_metadata_language`,
 * even though the server still mirrors them until the cutover.
 *
 * Both apps route through this one class deliberately: the two settings
 * screens have drifted apart before, and a behavior that lives here cannot be
 * present on one and missing on the other.
 */
class ProfileSettingsController(
    private val repository: SettingsRepository,
    /**
     * The pre-contract path, used when the server does not serve the canonical
     * settings API. Null disables the fallback entirely, which is what the
     * canonical-path tests want.
     */
    private val legacy: LegacyProfileSettings? = null,
) {

    /**
     * Whether the connected server can serve canonical settings at all.
     * [ServerUpgradeRequired] is not an error to swallow — the screen says so,
     * and playback continues on local defaults.
     */
    enum class Availability {
        /** Not probed yet. */
        UNKNOWN,
        AVAILABLE,
        /** The server predates the canonical settings API (404 on the contract). */
        SERVER_UPGRADE_REQUIRED,
        /** Reachable server, failed probe — transient, retryable. */
        UNAVAILABLE,
    }

    /** The resolved profile preferences a settings screen renders. */
    data class Snapshot(
        /** BCP 47 tag; "" is "no subtitle preference". */
        val subtitleLanguage: String = "",
        /** One of "auto", "always", "off". */
        val subtitleMode: String = DEFAULT_SUBTITLE_MODE,
        val showForcedSubtitles: Boolean = true,
        /** BCP 47 tag; "" inherits the library metadata language. */
        val metadataLanguage: String = "",
    )

    /** Probe result plus the values, so a screen loads both in one call. */
    data class LoadResult(
        val availability: Availability,
        val snapshot: Snapshot?,
    )

    /**
     * The outcome of one setter: whether the write landed, and what the server
     * resolves for these keys now.
     *
     * [snapshot] is what the screen should render — it may differ from what the
     * user just chose when policy narrowed the value or a device-scoped row
     * shadows the profile one. Null means the write landed but the re-resolve
     * did not; the caller keeps its optimistic value rather than rolling back a
     * change that did take effect.
     */
    data class WriteResult(
        val succeeded: Boolean,
        val snapshot: Snapshot?,
    )

    /**
     * Probes the contract and, when the server speaks it, resolves the profile
     * keys. A failed *probe* leaves the snapshot null so the caller keeps
     * whatever it had; a successful probe with a failed resolve is reported as
     * [Availability.UNAVAILABLE] for the same reason.
     */
    suspend fun load(): LoadResult {
        val availability = when (repository.contractCapabilities()) {
            is SettingsCapabilitiesResult.Available -> Availability.AVAILABLE
            is SettingsCapabilitiesResult.ServerUpgradeRequired ->
                Availability.SERVER_UPGRADE_REQUIRED
            is SettingsCapabilitiesResult.Error,
            is SettingsCapabilitiesResult.NetworkError -> Availability.UNAVAILABLE
        }
        // Remembered so the setters know which path to write on without
        // re-probing per keystroke.
        contractServed = availability == Availability.AVAILABLE

        if (availability != Availability.AVAILABLE) {
            // A server that never had the contract is not an error state: the
            // legacy columns still hold these four preferences, so read them
            // rather than leaving the screen on defaults it will then write
            // back over the top of the user's real settings.
            val fallback = legacy?.load()
            return LoadResult(
                availability = if (fallback != null) Availability.AVAILABLE else availability,
                snapshot = fallback,
            )
        }

        return when (val result = repository.getEffectiveValues(PROFILE_KEYS)) {
            is ApiResult.Success -> LoadResult(availability, snapshotOf(result.data))
            is ApiResult.Error, is ApiResult.NetworkError ->
                LoadResult(Availability.UNAVAILABLE, null)
        }
    }

    /**
     * False until [load] finds the contract. Starts true so a setter called
     * before any load still tries the canonical path first and only falls back
     * on its 404, rather than assuming the worst of an unprobed server.
     */
    private var contractServed: Boolean = true

    /**
     * Re-resolves every profile key after a successful write.
     *
     * A stored value is not necessarily the effective one. Policy can narrow
     * or lock a setting (`playback.preferred_quality` carries a `ceiling`
     * today, and the response type has carried `constrained`/`stored_value`
     * since the contract landed), and a `profile_device` row for the same key
     * outranks the `profile` row these setters write — the resolver answers
     * with the device id attached. In both cases the PUT succeeds and changes
     * nothing the user can see, so keeping the optimistic value would leave
     * the screen asserting a preference playback is not using.
     *
     * Returns null when the re-resolve itself fails, which is not an error the
     * caller should surface: the write landed, and the optimistic value is
     * still the best guess until the next load.
     */
    private suspend fun reresolve(): Snapshot? =
        when (val result = repository.getEffectiveValues(PROFILE_KEYS)) {
            is ApiResult.Success -> snapshotOf(result.data)
            is ApiResult.Error, is ApiResult.NetworkError -> null
        }

    /** [language] is a BCP 47 tag, or "" for no preference. */
    suspend fun setSubtitleLanguage(language: String): WriteResult =
        viaContractOrLegacy(
            canonical = { writeLanguage(SettingKeys.PLAYBACK_SUBTITLE_LANGUAGE, language) },
            legacyWrite = { it.update(subtitleLanguage = language.trim()) },
        )

    /** [mode] is a `playback.subtitle_mode` member: "auto", "always" or "off". */
    suspend fun setSubtitleMode(mode: String): WriteResult =
        viaContractOrLegacy(
            canonical = {
                write(SettingKeys.PLAYBACK_SUBTITLE_MODE, JsonPrimitive(normalizeSubtitleMode(mode)))
            },
            legacyWrite = { it.update(subtitleMode = normalizeSubtitleMode(mode)) },
        )

    suspend fun setShowForcedSubtitles(enabled: Boolean): WriteResult =
        viaContractOrLegacy(
            canonical = { write(SettingKeys.PLAYBACK_SHOW_FORCED_SUBTITLES, JsonPrimitive(enabled)) },
            legacyWrite = { it.update(showForcedSubtitles = enabled) },
        )

    /** [language] is a BCP 47 tag, or "" to inherit the library's language. */
    suspend fun setMetadataLanguage(language: String): WriteResult =
        viaContractOrLegacy(
            canonical = { writeLanguage(SettingKeys.CATALOG_METADATA_LANGUAGE, language) },
            legacyWrite = { it.update(metadataLanguage = language.trim()) },
        )

    /**
     * Writes on whichever path this server actually serves.
     *
     * The canonical path is tried whenever the contract is believed present.
     * A 404 from it means the belief was wrong — an unprobed server, or one
     * that changed under a long-lived session — so the fallback runs
     * immediately and the belief is corrected for subsequent writes. Any other
     * failure is a real failure and is reported as one; retrying a rejected
     * value on the legacy path would only store what the contract refused.
     */
    private suspend fun viaContractOrLegacy(
        canonical: suspend () -> ApiResult<Unit>,
        legacyWrite: suspend (LegacyProfileSettings) -> Snapshot?,
    ): WriteResult {
        val fallback = legacy
        if (contractServed) {
            val result = canonical()
            if (result is ApiResult.Success) return WriteResult(true, reresolve())
            val absent = result is ApiResult.Error && result.code == HTTP_NOT_FOUND
            if (!absent || fallback == null) return WriteResult(false, null)
            contractServed = false
        }
        if (fallback == null) return WriteResult(false, null)
        val snapshot = legacyWrite(fallback)
        return WriteResult(succeeded = snapshot != null, snapshot = snapshot)
    }

    private suspend fun resolved(write: ApiResult<Unit>): WriteResult =
        if (write is ApiResult.Success) {
            WriteResult(succeeded = true, snapshot = reresolve())
        } else {
            WriteResult(succeeded = false, snapshot = null)
        }

    private suspend fun writeLanguage(key: String, language: String): ApiResult<Unit> {
        val tag = language.trim()
        // The store spells "no preference" as the empty string; the contract
        // spells it as no row at all (the server's language_tag validator
        // refuses ""). Clearing rather than writing null keeps the two the
        // same statement and matches how the server mirrors the legacy column.
        return if (tag.isEmpty()) repository.clearProfileValue(key) else write(key, JsonPrimitive(tag))
    }

    private suspend fun write(key: String, value: JsonElement): ApiResult<Unit> =
        when (val result = repository.setProfileValue(key, value)) {
            is ApiResult.Success -> ApiResult.Success(Unit)
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }

    private fun snapshotOf(effective: Map<String, EffectiveSettingValue>): Snapshot =
        Snapshot(
            subtitleLanguage = effective.stringOrEmpty(SettingKeys.PLAYBACK_SUBTITLE_LANGUAGE),
            subtitleMode = normalizeSubtitleMode(
                effective.stringOrEmpty(SettingKeys.PLAYBACK_SUBTITLE_MODE),
            ),
            showForcedSubtitles = effective.boolOr(SettingKeys.PLAYBACK_SHOW_FORCED_SUBTITLES, true),
            metadataLanguage = effective.stringOrEmpty(SettingKeys.CATALOG_METADATA_LANGUAGE),
        )

    internal companion object {
        const val DEFAULT_SUBTITLE_MODE = "auto"
        val SUBTITLE_MODES = setOf("auto", "always", "off")

        /**
         * Every profile-scoped key these screens read, in one round trip.
         *
         * Quality is deliberately absent: on Android the two quality axes are
         * device-scoped (the store owns them and "Reset Playback Overrides"
         * clears them), so a profile-scope write would be shadowed by this
         * device's own row and the picker would appear not to save.
         */
        val PROFILE_KEYS: List<String> = listOf(
            SettingKeys.PLAYBACK_SUBTITLE_LANGUAGE,
            SettingKeys.PLAYBACK_SUBTITLE_MODE,
            SettingKeys.PLAYBACK_SHOW_FORCED_SUBTITLES,
            SettingKeys.CATALOG_METADATA_LANGUAGE,
        )

        /**
         * A server without the canonical settings API answers 404 to the whole
         * family, which is what distinguishes "this route does not exist here"
         * from "this value was rejected".
         */
        private const val HTTP_NOT_FOUND = 404

        fun normalizeSubtitleMode(value: String): String {
            val v = value.trim().lowercase()
            // The legacy empty string means unset, not a fourth mode.
            return if (v in SUBTITLE_MODES) v else DEFAULT_SUBTITLE_MODE
        }

        fun Map<String, EffectiveSettingValue>.stringOrEmpty(key: String): String {
            val value = this[key]?.value ?: return ""
            if (value is JsonNull) return ""
            return runCatching { value.jsonPrimitive.content }.getOrDefault("")
        }

        fun Map<String, EffectiveSettingValue>.boolOr(key: String, fallback: Boolean): Boolean {
            val value = this[key]?.value ?: return fallback
            return runCatching { value.jsonPrimitive.booleanOrNull }.getOrNull() ?: fallback
        }

        fun Map<String, EffectiveSettingValue>.intOrNull(key: String): Int? {
            val value = this[key]?.value ?: return null
            if (value is JsonNull) return null
            return runCatching { value.jsonPrimitive.intOrNull }.getOrNull()
        }
    }
}
