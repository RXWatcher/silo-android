package org.siloserver.silo.model.settings

import org.siloserver.silo.playback.canonicalSubtitleLanguage

/**
 * The language choices the settings UI offers, and the wire values they map to.
 *
 * The server's settings contract declares `playback.audio_language` and the
 * profile's `subtitle_language` as BCP 47 language tags, and validates them as
 * such. Sending a display label ("English") is rejected outright — and was
 * never useful even when the server accepted it, because
 * `setPreferredAudioLanguage("English")` never matches a track tagged `eng`.
 *
 * One table rather than a list per screen: the phone and TV UIs, audio and
 * subtitle, previously carried four copies that had already drifted apart —
 * subtitles on TV stored codes while the phone stored labels, and audio stored
 * labels on both. A single source means adding a language cannot leave one
 * surface behind.
 */
object LanguageOptions {
    /** Wire value meaning "no preference"; the server stores this as null. */
    const val UNSET = ""

    /**
     * (wire tag, display label), in the order the pickers show them. The first
     * entry is the unset choice, whose label differs by context — "Default" for
     * audio, "Off" for subtitles — so callers supply it.
     *
     * This mirrors the web client's ISO 639-1 table
     * (`web/src/player/utils/languageNames.ts` in silo-server), in its order,
     * because that is the widest first-party list and the server imposes no
     * enum of its own: the settings contract types
     * `playback.subtitle_language` as `language_tag`, validated only for BCP 47
     * shape. A language missing here was never a server limitation — it was
     * simply a shorter picker, which left users who had set e.g. Dutch on the
     * web unable to set or re-select it on Android.
     */
    val tags: List<Pair<String, String>> = listOf(
        "en" to "English",
        "es" to "Spanish",
        "fr" to "French",
        "de" to "German",
        "it" to "Italian",
        "pt" to "Portuguese",
        "nl" to "Dutch",
        "pl" to "Polish",
        "ru" to "Russian",
        "zh" to "Chinese",
        "ja" to "Japanese",
        "ko" to "Korean",
        "ar" to "Arabic",
        "tr" to "Turkish",
        "sv" to "Swedish",
        "da" to "Danish",
        "no" to "Norwegian",
        "fi" to "Finnish",
        "hu" to "Hungarian",
        "cs" to "Czech",
        "ro" to "Romanian",
        "he" to "Hebrew",
        "th" to "Thai",
        "vi" to "Vietnamese",
        "el" to "Greek",
        "bg" to "Bulgarian",
        "hr" to "Croatian",
        "sk" to "Slovak",
        "sl" to "Slovenian",
        "uk" to "Ukrainian",
        "id" to "Indonesian",
        "ms" to "Malay",
        "hi" to "Hindi",
        "ta" to "Tamil",
        "te" to "Telugu",
        "bn" to "Bengali",
        "fa" to "Persian",
    )

    /** The full option list for a picker, led by [unsetLabel]. */
    fun options(unsetLabel: String): List<Pair<String, String>> =
        listOf(UNSET to unsetLabel) + tags

    /**
     * The label for a stored wire value.
     *
     * A tag outside the picker table ("pt-BR", "zh-Hant" synced from another
     * surface) is echoed back as itself: it is a real, active preference, and
     * labeling it as unset would tell the user a preference playback still
     * applies is off. Only values that aren't tags at all — legacy display
     * labels — read as unset, since the server does not hold them.
     */
    fun label(wire: String?, unsetLabel: String): String {
        if (wire.isNullOrBlank()) return unsetLabel
        tags.firstOrNull { it.first == wire }?.let { return it.second }
        return if (isPreservableTag(wire)) wire else unsetLabel
    }

    /**
     * A human-readable name for a language code that came from server data
     * rather than from a picker — catalog filter facets, for instance.
     *
     * Unlike [label] this canonicalises first, because catalog values are
     * whatever the ingest wrote before the server's own canonicalisation ran,
     * and a facet list should read "Dutch" whether the file said `nl`, `nld`
     * or `dut`. An unrecognised code is returned as-is: showing the raw tag is
     * honest, and better than hiding a filter the user can still apply.
     */
    fun displayLanguage(code: String): String {
        val canonical = canonicalSubtitleLanguage(code) ?: return code
        return tags.firstOrNull { it.first == canonical }?.second ?: code
    }

    /**
     * The wire value for a label the user picked. Falls back to [UNSET], which
     * is the one value the server always accepts.
     */
    fun wireValue(label: String?): String =
        tags.firstOrNull { it.second == label }?.first ?: UNSET

    /**
     * Translates a value stored by a build that persisted display names.
     *
     * Those rows are already on devices in the field. They are not tags, so the
     * server rejects them and track matching never hit on them — but left alone
     * they would keep being read and re-sent. A value that is already a known
     * tag, or already unset, is returned unchanged; a known label becomes its
     * tag. Anything else that is tag-shaped ("pt-BR", "zh-Hant", an alias
     * like "eng") passes through untouched — the table lists only the languages the
     * pickers offer, and a valid tag synced from another surface must not be
     * erased just because it is outside that list. Only values that are neither
     * a plausible tag nor a known label (i.e. legacy display names we no longer
     * recognize) become [UNSET].
     */
    fun migrateLegacyValue(stored: String?): String = when {
        stored.isNullOrBlank() -> UNSET
        tags.any { it.first == stored } -> stored
        tags.any { it.second == stored } -> wireValue(stored)
        isPreservableTag(stored) -> stored
        else -> UNSET
    }

    /**
     * Loose BCP 47 shape check: 2-3 letter primary subtag, optional script /
     * region subtags. Deliberately permissive — the server is the validator;
     * this only has to separate tags from display names like "English".
     * The old pickers' unset labels are excluded by name: "Off" is 3 letters
     * and would otherwise pass as a tag.
     */
    private fun isPreservableTag(value: String): Boolean =
        !value.equals("Off", ignoreCase = true) &&
            !value.equals("Default", ignoreCase = true) &&
            Regex("^[a-zA-Z]{2,3}([-_][a-zA-Z0-9]{2,8})*$").matches(value) &&
            canonicalSubtitleLanguage(value) != null
}
