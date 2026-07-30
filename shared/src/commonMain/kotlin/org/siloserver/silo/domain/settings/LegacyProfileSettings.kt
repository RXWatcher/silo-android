package org.siloserver.silo.domain.settings

import org.siloserver.silo.model.profile.UpdateProfileRequest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.ProfileRepository

/**
 * The pre-contract path for the four profile playback preferences, kept alive
 * for servers that do not serve the canonical settings API.
 *
 * These preferences moved to `/settings/values/…` on the assumption that every
 * reachable server would speak the contract. Servers predating it answer 404
 * to the whole family — contract probe, batched read and write alike — so on
 * those the settings screen wrote into a void: every change was accepted
 * optimistically, rejected, and rolled back, for every setting this controller
 * owns, not just subtitles. The columns on `PUT /profiles/{id}` still work
 * there, and the server mirrors them into canonical rows once upgraded, so
 * falling back costs nothing and loses nothing.
 */
interface LegacyProfileSettings {

    /** The four values as the profile record holds them, or null if unreadable. */
    suspend fun load(): ProfileSettingsController.Snapshot?

    /**
     * Writes only the named preferences; nulls are left untouched.
     *
     * Returns the profile's values after the write so the caller can render
     * what the server actually stored, or null when the write failed.
     */
    suspend fun update(
        subtitleLanguage: String? = null,
        subtitleMode: String? = null,
        showForcedSubtitles: Boolean? = null,
        metadataLanguage: String? = null,
    ): ProfileSettingsController.Snapshot?
}

/**
 * [LegacyProfileSettings] over the profile endpoint.
 *
 * Unset is the empty string here rather than an absent row: that is how the
 * legacy columns have always spelled "no preference", and it is why the
 * canonical path needs a DELETE for the same intent.
 */
class ProfileBackedLegacySettings(
    private val profiles: ProfileRepository,
) : LegacyProfileSettings {

    override suspend fun load(): ProfileSettingsController.Snapshot? =
        when (val result = profiles.getActiveProfileResult()) {
            is ApiResult.Success -> ProfileSettingsController.Snapshot(
                subtitleLanguage = result.data.subtitleLanguage.orEmpty(),
                subtitleMode = ProfileSettingsController.normalizeSubtitleMode(result.data.subtitleMode.orEmpty()),
                showForcedSubtitles = result.data.showForcedSubtitles ?: true,
                metadataLanguage = result.data.preferredMetadataLanguage.orEmpty(),
            )
            is ApiResult.Error, is ApiResult.NetworkError -> null
        }

    override suspend fun update(
        subtitleLanguage: String?,
        subtitleMode: String?,
        showForcedSubtitles: Boolean?,
        metadataLanguage: String?,
    ): ProfileSettingsController.Snapshot? {
        val request = UpdateProfileRequest(
            subtitleLanguage = subtitleLanguage,
            subtitleMode = subtitleMode,
            showForcedSubtitles = showForcedSubtitles,
            preferredMetadataLanguage = metadataLanguage,
        )
        return when (val result = profiles.updateActiveProfile(request)) {
            is ApiResult.Success -> ProfileSettingsController.Snapshot(
                subtitleLanguage = result.data.subtitleLanguage.orEmpty(),
                subtitleMode = ProfileSettingsController.normalizeSubtitleMode(result.data.subtitleMode.orEmpty()),
                showForcedSubtitles = result.data.showForcedSubtitles ?: true,
                metadataLanguage = result.data.preferredMetadataLanguage.orEmpty(),
            )
            is ApiResult.Error, is ApiResult.NetworkError -> null
        }
    }
}
