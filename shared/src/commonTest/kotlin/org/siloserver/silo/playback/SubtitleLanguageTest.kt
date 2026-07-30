package org.siloserver.silo.playback

import org.siloserver.silo.model.settings.LanguageOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Auto-selection compares a stored preference against a track's language with
 * both sides run through [canonicalSubtitleLanguage], so this function is what
 * decides whether a chosen language actually turns subtitles on.
 */
class SubtitleLanguageTest {

    @Test
    fun everyPickerTagIsAlreadyCanonical() {
        // A picker option that canonicalises to something else would never
        // equal the canonical form of a matching track.
        for ((wire, label) in LanguageOptions.tags) {
            assertEquals(wire, canonicalSubtitleLanguage(wire), "$label ($wire) is not canonical")
        }
    }

    @Test
    fun everyPickerLanguageMatchesItsThreeLetterTrackTag() {
        // Servers commonly expose ISO 639-2 while the pickers persist 639-1.
        // Before this table was completed, only seven languages survived the
        // round trip; Italian, Portuguese, Korean, Chinese and Russian were
        // offered in the picker but could not match a bibliographic tag.
        val cases = mapOf(
            "ita" to "it",
            "por" to "pt",
            "kor" to "ko",
            "chi" to "zh",
            "zho" to "zh",
            "rus" to "ru",
            "pol" to "pl",
            "swe" to "sv",
            "ces" to "cs",
            "cze" to "cs",
            "ell" to "el",
            "gre" to "el",
            "fas" to "fa",
            "per" to "fa",
        )
        for ((trackTag, expected) in cases) {
            assertEquals(expected, canonicalSubtitleLanguage(trackTag), "track tag $trackTag")
        }
    }

    @Test
    fun dutchMatchesAcrossEveryFormAServerMightReport() {
        // The reported case: Dutch chosen as the default subtitle language.
        for (form in listOf("nl", "nld", "dut", "NL", "nl-NL", "nl_BE")) {
            assertEquals("nl", canonicalSubtitleLanguage(form), "form $form")
        }
    }

    @Test
    fun legacyIso639CodesStillResolve() {
        // Pre-1989 codes, still emitted by older muxing tools.
        assertEquals("he", canonicalSubtitleLanguage("iw"))
        assertEquals("id", canonicalSubtitleLanguage("in"))
    }

    @Test
    fun norwegianVariantsStayDistinctExactlyAsTheServerKeepsThem() {
        // The server's canonical_language_code maps nob to nb and nno to nn,
        // NOT to no. Folding them here would look friendlier and then fail to
        // match: the catalog stores what the server canonicalised, so a
        // Bokmal file is "nb" on both sides or it matches nothing.
        assertEquals("no", canonicalSubtitleLanguage("nor"))
        assertEquals("nb", canonicalSubtitleLanguage("nob"))
        assertEquals("nn", canonicalSubtitleLanguage("nno"))
    }

    @Test
    fun regionAndScriptSuffixesDoNotSplitALanguage() {
        assertEquals("pt", canonicalSubtitleLanguage("pt-BR"))
        assertEquals("zh", canonicalSubtitleLanguage("zh-Hant"))
        assertEquals("en", canonicalSubtitleLanguage("en_GB"))
    }

    @Test
    fun absentAndUndeterminedLanguagesResolveToNothing() {
        assertNull(canonicalSubtitleLanguage(null))
        assertNull(canonicalSubtitleLanguage(""))
        assertNull(canonicalSubtitleLanguage("   "))
        assertNull(canonicalSubtitleLanguage("und"))
        assertNull(canonicalSubtitleLanguage("UND"))
    }

    @Test
    fun anUnknownTagPassesThroughRatherThanBeingDropped() {
        // Outside the table entirely — the server does the same (its ELSE
        // branch returns lower(trim(code))), so both sides still agree and the
        // preference keeps comparing equal to itself.
        assertEquals("tlh", canonicalSubtitleLanguage("tlh"))
        assertEquals("sme", canonicalSubtitleLanguage("SME"))
    }

    @Test
    fun aliasesCoverLanguagesBeyondThePickerToo() {
        // The table mirrors the server's full map rather than just the offered
        // list, so an unlisted language still compares correctly against a
        // preference synced from another surface.
        assertEquals("ca", canonicalSubtitleLanguage("cat"))
        assertEquals("sr", canonicalSubtitleLanguage("srp"))
        assertEquals("is", canonicalSubtitleLanguage("ice"))
        assertEquals("cy", canonicalSubtitleLanguage("wel"))
        assertEquals("eu", canonicalSubtitleLanguage("baq"))
    }

    @Test
    fun everyOfferedLanguageIsReachableFromItsThreeLetterForm() {
        // Guards the pairing the pickers depend on: if a language is offered,
        // some 639-2 spelling of it must canonicalise back to the offered tag,
        // or a server exposing that spelling can never match the choice.
        //
        // Every offered tag must HAVE a spelling listed and every listed
        // spelling must resolve. An earlier version defaulted a tag with no
        // entry to "reachable", which meant adding a language and forgetting
        // its aliases — precisely the drift this guards — passed silently.
        val offered = LanguageOptions.tags.map { it.first }
        val unmapped = offered.filterNot(THREE_LETTER_FORMS::containsKey)
        assertEquals(emptyList(), unmapped, "offered languages with no 639-2 spelling listed")

        val unreachable = offered.flatMap { tag ->
            THREE_LETTER_FORMS.getValue(tag)
                .filter { canonicalSubtitleLanguage(it) != tag }
                .map { "$it should canonicalise to $tag, got ${canonicalSubtitleLanguage(it)}" }
        }
        assertEquals(emptyList(), unreachable, "639-2 spellings that do not resolve")

        // And nothing stale: a spelling listed for a language the picker no
        // longer offers would quietly stop being exercised.
        val orphaned = THREE_LETTER_FORMS.keys.filterNot(offered::contains)
        assertEquals(emptyList(), orphaned.toList(), "spellings listed for unoffered languages")
    }

    private companion object {
        /** A representative 639-2 spelling per offered language, where one exists. */
        val THREE_LETTER_FORMS = mapOf(
            "en" to listOf("eng"), "es" to listOf("spa"), "fr" to listOf("fre", "fra"),
            "de" to listOf("ger", "deu"), "it" to listOf("ita"), "pt" to listOf("por"),
            "nl" to listOf("dut", "nld"), "pl" to listOf("pol"), "ru" to listOf("rus"),
            "zh" to listOf("chi", "zho"), "ja" to listOf("jpn"), "ko" to listOf("kor"),
            "ar" to listOf("ara"), "tr" to listOf("tur"), "sv" to listOf("swe"),
            "da" to listOf("dan"), "no" to listOf("nor"), "fi" to listOf("fin"),
            "hu" to listOf("hun"), "cs" to listOf("cze", "ces"), "ro" to listOf("rum", "ron"),
            "he" to listOf("heb"), "th" to listOf("tha"), "vi" to listOf("vie"),
            "el" to listOf("gre", "ell"), "bg" to listOf("bul"), "hr" to listOf("hrv"),
            "sk" to listOf("slo", "slk"), "sl" to listOf("slv"), "uk" to listOf("ukr"),
            "id" to listOf("ind"), "ms" to listOf("may", "msa"), "hi" to listOf("hin"),
            "ta" to listOf("tam"), "te" to listOf("tel"), "bn" to listOf("ben"),
            "fa" to listOf("per", "fas"),
        )
    }
}
