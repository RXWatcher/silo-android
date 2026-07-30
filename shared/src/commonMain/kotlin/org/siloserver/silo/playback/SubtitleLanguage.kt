package org.siloserver.silo.playback

/**
 * Canonical primary subtitle language shared by catalog persistence and
 * mounted-player identity matching.
 *
 * Servers commonly expose ISO 639-2 aliases while Android decoders expose
 * ISO 639-1 tags. Region/script suffixes do not identify a different subtitle
 * artifact for the selection fallback, so matching uses the primary language.
 */
/**
 * A resolved subtitle preference, with "" collapsed back to null.
 *
 * The two representations mean the same thing in the settings store — the
 * contract spells "no preference" as JSON null, the store spells it as the
 * empty string — but they mean opposite things to subtitle auto-selection: a
 * blank-but-present language is read as an explicit "off", while null means
 * "nothing chosen, decide normally". Any preference crossing from settings
 * into playback goes through here so the store's spelling cannot be mistaken
 * for a user's choice.
 */
fun String?.orNullIfBlank(): String? = this?.takeIf { it.isNotBlank() }

fun canonicalSubtitleLanguage(language: String?): String? {
    val primary = language
        ?.trim()
        ?.takeUnless { it.isBlank() || it.equals("und", ignoreCase = true) }
        ?.lowercase()
        ?.replace('_', '-')
        ?.substringBefore('-')
        ?: return null
    return THREE_LETTER_ALIASES[primary] ?: primary
}

/**
 * ISO 639-2 (and legacy ISO 639-1) aliases for the languages the pickers offer,
 * mapped to the 639-1 tag those pickers persist.
 *
 * Both sides of every comparison run through [canonicalSubtitleLanguage], so an
 * alias missing here is not cosmetic: a track tagged `ita` never matches a
 * stored preference of `it`, and the language silently fails to auto-select.
 * The table previously covered seven languages while the pickers offered ten,
 * so Italian, Portuguese, Korean, Chinese and Russian were already affected on
 * any server exposing bibliographic codes.
 *
 * Where 639-2 has both a bibliographic (B) and terminological (T) code, both
 * appear. `iw`/`in` are the pre-1989 639-1 codes for Hebrew and Indonesian,
 * still emitted by older tooling. Norwegian folds Bokmål and Nynorsk into
 * `no`: they are separate codes but one choice in the picker, and a viewer
 * asking for Norwegian subtitles wants whichever the release carries.
 */
private val THREE_LETTER_ALIASES: Map<String, String> = mapOf(
    "eng" to "en",
    "spa" to "es",
    "fre" to "fr", "fra" to "fr",
    "ger" to "de", "deu" to "de",
    "ita" to "it",
    "por" to "pt",
    "dut" to "nl", "nld" to "nl",
    "pol" to "pl",
    "rus" to "ru",
    "chi" to "zh", "zho" to "zh",
    "jpn" to "ja",
    "kor" to "ko",
    "ara" to "ar",
    "tur" to "tr",
    "swe" to "sv",
    "dan" to "da",
    "nor" to "no", "nob" to "no", "nno" to "no",
    "fin" to "fi",
    "hun" to "hu",
    "cze" to "cs", "ces" to "cs",
    "rum" to "ro", "ron" to "ro",
    "heb" to "he", "iw" to "he",
    "tha" to "th",
    "vie" to "vi",
    "gre" to "el", "ell" to "el",
    "bul" to "bg",
    "hrv" to "hr",
    "slo" to "sk", "slk" to "sk",
    "slv" to "sl",
    "ukr" to "uk",
    "ind" to "id", "in" to "id",
    "may" to "ms", "msa" to "ms",
    "hin" to "hi",
    "tam" to "ta",
    "tel" to "te",
    "ben" to "bn",
    "per" to "fa", "fas" to "fa",
)
