package org.siloserver.silo.playback

import org.siloserver.silo.model.settings.LanguageOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun norwegianVariantsFoldIntoTheSingleOfferedChoice() {
        // Bokmal and Nynorsk are distinct codes but one picker entry; a viewer
        // asking for Norwegian wants whichever the release carries.
        for (form in listOf("nor", "nob", "nno")) {
            assertEquals("no", canonicalSubtitleLanguage(form), "form $form")
        }
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
        // The table covers the offered languages; anything else is still a
        // real preference and must keep comparing equal to itself.
        assertEquals("sr", canonicalSubtitleLanguage("sr"))
        assertEquals("cat", canonicalSubtitleLanguage("cat"))
    }

    @Test
    fun everyAliasResolvesToALanguageThePickerOffers() {
        // An alias pointing at a tag no picker lists would be dead weight and
        // a sign the two tables had drifted.
        val offered = LanguageOptions.tags.map { it.first }.toSet()
        val aliases = listOf(
            "eng", "spa", "fre", "fra", "ger", "deu", "ita", "por", "dut", "nld",
            "pol", "rus", "chi", "zho", "jpn", "kor", "ara", "tur", "swe", "dan",
            "nor", "nob", "nno", "fin", "hun", "cze", "ces", "rum", "ron", "heb",
            "iw", "tha", "vie", "gre", "ell", "bul", "hrv", "slo", "slk", "slv",
            "ukr", "ind", "in", "may", "msa", "hin", "tam", "tel", "ben", "per", "fas",
        )
        for (alias in aliases) {
            val canonical = canonicalSubtitleLanguage(alias)
            assertTrue(canonical in offered, "$alias resolves to $canonical, which no picker offers")
        }
    }
}
