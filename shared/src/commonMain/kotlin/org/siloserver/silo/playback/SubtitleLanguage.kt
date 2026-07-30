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
 * This is a faithful mirror of the server's `canonical_language_code`
 * (migrations/sql/121_canonicalize_language_codes.sql, itself mirroring the Go
 * helper in `internal/lang`), deliberately copied rather than reinvented. The
 * catalog stores languages already canonicalised by that function, so any
 * disagreement here shows up as a preference that silently fails to match —
 * hand-rolling a "close enough" subset is how that happens. It therefore
 * covers far more languages than the pickers offer, which costs nothing and
 * means an unlisted language still compares correctly.
 *
 * Note Norwegian follows the server in keeping `nob` → `nb` and `nno` → `nn`
 * distinct from `nor` → `no`, rather than folding all three together.
 */
private val THREE_LETTER_ALIASES: Map<String, String> = mapOf(
    "eng" to "en",
    "jpn" to "ja",
    "fra" to "fr", "fre" to "fr",
    "deu" to "de", "ger" to "de",
    "spa" to "es",
    "ita" to "it",
    "por" to "pt",
    "rus" to "ru",
    "zho" to "zh", "chi" to "zh",
    "kor" to "ko",
    "ara" to "ar",
    "nld" to "nl", "dut" to "nl",
    "pol" to "pl",
    "swe" to "sv",
    "dan" to "da",
    "fin" to "fi",
    "tur" to "tr",
    "ces" to "cs", "cze" to "cs",
    "slk" to "sk", "slo" to "sk",
    "hun" to "hu",
    "heb" to "he",
    "tha" to "th",
    "ind" to "id",
    "msa" to "ms", "may" to "ms",
    "vie" to "vi",
    "ukr" to "uk",
    "ron" to "ro", "rum" to "ro",
    "bul" to "bg",
    "hrv" to "hr",
    "srp" to "sr",
    "slv" to "sl",
    "ell" to "el", "gre" to "el",
    "hin" to "hi",
    "tam" to "ta",
    "tel" to "te",
    "ben" to "bn",
    "fas" to "fa", "per" to "fa",
    "cat" to "ca",
    "lit" to "lt",
    "lav" to "lv",
    "est" to "et",
    "isl" to "is", "ice" to "is",
    "mlt" to "mt",
    "gle" to "ga",
    "cym" to "cy", "wel" to "cy",
    "mya" to "my", "bur" to "my",
    "khm" to "km",
    "lao" to "lo",
    "sin" to "si",
    "mar" to "mr",
    "pan" to "pa",
    "guj" to "gu",
    "kan" to "kn",
    "mal" to "ml",
    "urd" to "ur",
    "nep" to "ne",
    "aze" to "az",
    "kat" to "ka", "geo" to "ka",
    "hye" to "hy", "arm" to "hy",
    "kaz" to "kk",
    "uzb" to "uz",
    "mon" to "mn",
    "mlg" to "mg",
    "swa" to "sw",
    "zul" to "zu",
    "afr" to "af",
    "amh" to "am",
    "lat" to "la",
    "epo" to "eo",
    "baq" to "eu", "eus" to "eu",
    "glg" to "gl",
    "nor" to "no",
    "nob" to "nb",
    "nno" to "nn",
    // Pre-1989 ISO 639-1 spellings. Java's Locale still normalises Hebrew to
    // "iw" and Indonesian to "in", so these reach us from the platform side.
    // The server's map does not carry them (they are 639-1, not 639-2) and
    // passes them through unchanged, so a file ingested with one of these
    // spellings will not agree with a preference stored here — worth adding
    // server-side too.
    "iw" to "he",
    "in" to "id",
)
