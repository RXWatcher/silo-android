package org.siloserver.silo.model.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LanguageOptionsTest {

    @Test
    fun everyOptionPersistsALanguageTagRatherThanItsLabel() {
        // The server validates playback.audio_language and the profile's
        // subtitle_language as BCP 47. A display name is rejected outright, and
        // never matched a track even when it was accepted.
        val tagShape = Regex("^[a-z]{2,3}$")
        for ((wire, label) in LanguageOptions.tags) {
            assertTrue(tagShape.matches(wire), "$label persists \"$wire\", which is not a tag")
        }
    }

    @Test
    fun optionsLeadWithTheUnsetChoiceUnderTheCallersLabel() {
        val audio = LanguageOptions.options(unsetLabel = "Default")
        val subtitles = LanguageOptions.options(unsetLabel = "Off")

        assertEquals(LanguageOptions.UNSET to "Default", audio.first())
        assertEquals(LanguageOptions.UNSET to "Off", subtitles.first())
        assertEquals(LanguageOptions.tags.size + 1, audio.size)
        assertEquals(audio.drop(1), subtitles.drop(1))
    }

    @Test
    fun labelsAndWireValuesRoundTrip() {
        for ((wire, label) in LanguageOptions.tags) {
            assertEquals(label, LanguageOptions.label(wire, unsetLabel = "Default"))
            assertEquals(wire, LanguageOptions.wireValue(label))
        }
    }

    @Test
    fun anUnsetOrLegacyLabelValueReadsAsTheUnsetLabel() {
        assertEquals("Default", LanguageOptions.label("", unsetLabel = "Default"))
        assertEquals("Default", LanguageOptions.label(null, unsetLabel = "Default"))
        // A value stored by a build that persisted labels must not be echoed
        // back as if it were a choice the server holds.
        assertEquals("Off", LanguageOptions.label("English", unsetLabel = "Off"))
    }

    @Test
    fun aPreservedTagOutsideTheTableReadsAsItselfNotAsUnset() {
        // migrateLegacyValue passes these through, so playback still applies
        // them; showing "Off"/"Default" would claim an active preference is
        // disabled. Region- and script-qualified tags stay outside the table
        // even though their primary languages are in it.
        assertEquals("pt-BR", LanguageOptions.label("pt-BR", unsetLabel = "Default"))
        assertEquals("zh-Hant", LanguageOptions.label("zh-Hant", unsetLabel = "Off"))
    }

    @Test
    fun thePickerMirrorsTheWebClientsTableExactly() {
        // The whole claim of this list is that it mirrors the web's ISO 639-1
        // table (silo-server web/src/player/utils/languageNames.ts) IN ITS
        // ORDER. Asserting only a count and one language would let a wrong
        // label or a silent reordering through, so the table is pinned whole:
        // if the web list changes, this fails and someone decides deliberately.
        assertEquals(
            listOf(
                "en" to "English", "es" to "Spanish", "fr" to "French",
                "de" to "German", "it" to "Italian", "pt" to "Portuguese",
                "nl" to "Dutch", "pl" to "Polish", "ru" to "Russian",
                "zh" to "Chinese", "ja" to "Japanese", "ko" to "Korean",
                "ar" to "Arabic", "tr" to "Turkish", "sv" to "Swedish",
                "da" to "Danish", "no" to "Norwegian", "fi" to "Finnish",
                "hu" to "Hungarian", "cs" to "Czech", "ro" to "Romanian",
                "he" to "Hebrew", "th" to "Thai", "vi" to "Vietnamese",
                "el" to "Greek", "bg" to "Bulgarian", "hr" to "Croatian",
                "sk" to "Slovak", "sl" to "Slovenian", "uk" to "Ukrainian",
                "id" to "Indonesian", "ms" to "Malay", "hi" to "Hindi",
                "ta" to "Tamil", "te" to "Telugu", "bn" to "Bengali",
                "fa" to "Persian",
            ),
            LanguageOptions.tags,
        )
        // The reported case, called out by name so a regression names itself.
        assertEquals("Dutch", LanguageOptions.label("nl", unsetLabel = "Off"))
        assertEquals("nl", LanguageOptions.wireValue("Dutch"))
    }

    @Test
    fun noLanguageIsListedTwice() {
        assertEquals(
            LanguageOptions.tags.size,
            LanguageOptions.tags.map { it.first }.toSet().size,
            "duplicate wire tag",
        )
        assertEquals(
            LanguageOptions.tags.size,
            LanguageOptions.tags.map { it.second }.toSet().size,
            "duplicate display label",
        )
    }

    @Test
    fun legacyLabelValuesMigrateToTheirTags() {
        // What older builds actually wrote to DataStore and PUT to the server.
        assertEquals("en", LanguageOptions.migrateLegacyValue("English"))
        assertEquals("ja", LanguageOptions.migrateLegacyValue("Japanese"))
        // Already-correct values survive untouched.
        assertEquals("en", LanguageOptions.migrateLegacyValue("en"))
        assertEquals("pt", LanguageOptions.migrateLegacyValue("pt"))
        // Unset stays unset, and anything unrecognized clears rather than
        // continuing to fail validation on every flush.
        assertEquals(LanguageOptions.UNSET, LanguageOptions.migrateLegacyValue(""))
        assertEquals(LanguageOptions.UNSET, LanguageOptions.migrateLegacyValue(null))
        assertEquals(LanguageOptions.UNSET, LanguageOptions.migrateLegacyValue("Klingon"))
    }

    @Test
    fun validTagsOutsideThePickerTableSurviveMigration() {
        // The table lists only the languages the pickers offer. A tag synced
        // from another surface (web offers 37 languages) is server-valid and
        // must pass through, not be erased to "no preference".
        assertEquals("nl", LanguageOptions.migrateLegacyValue("nl"))
        assertEquals("hi", LanguageOptions.migrateLegacyValue("hi"))
        assertEquals("pt-BR", LanguageOptions.migrateLegacyValue("pt-BR"))
        assertEquals("zh-Hant", LanguageOptions.migrateLegacyValue("zh-Hant"))
        assertEquals("eng", LanguageOptions.migrateLegacyValue("eng"))
    }

    @Test
    fun legacyUnsetLabelsMigrateToUnset() {
        // "Off"/"Default" were the old pickers' unset rows; "Off" is short
        // enough to look tag-shaped and must still clear.
        assertEquals(LanguageOptions.UNSET, LanguageOptions.migrateLegacyValue("Off"))
        assertEquals(LanguageOptions.UNSET, LanguageOptions.migrateLegacyValue("Default"))
    }

    @Test
    fun facetCodesRenderAsLanguageNamesWhateverSpellingTheCatalogHolds() {
        // Catalog vocabulary carries whatever ingest wrote. A filter row
        // reading "dut" tells the viewer nothing.
        assertEquals("Dutch", LanguageOptions.displayLanguage("nl"))
        assertEquals("Dutch", LanguageOptions.displayLanguage("dut"))
        assertEquals("Dutch", LanguageOptions.displayLanguage("nld"))
        assertEquals("English", LanguageOptions.displayLanguage("eng"))
        assertEquals("Portuguese", LanguageOptions.displayLanguage("pt-BR"))
    }

    @Test
    fun anUnlabelledFacetCodeIsShownRatherThanHidden() {
        // Outside the offered list: the raw tag is honest, and the filter
        // still applies. Blanking it would hide a usable filter.
        assertEquals("tlh", LanguageOptions.displayLanguage("tlh"))
        // Catalan canonicalises to "ca" but no picker offers it, so the code
        // the facet actually filters by is what gets shown — display and wire
        // value stay the same string.
        assertEquals("cat", LanguageOptions.displayLanguage("cat"))
    }

    @Test
    fun theLibrarysOwnLanguagesComeFirst() {
        // The signal the device locale cannot give on a TV: a viewer whose
        // catalog carries Dutch is far likelier to want Dutch than Bengali.
        val options = LanguageOptions.optionsPrioritising(
            unsetLabel = "Off",
            libraryLanguages = listOf("nld", "en", "de"),
        )

        assertEquals(LanguageOptions.UNSET to "Off", options.first())
        assertEquals(listOf("Dutch", "English", "German"), options.drop(1).take(3).map { it.second })
        // Nothing is lost from the tail.
        assertEquals(LanguageOptions.tags.size + 1, options.size)
    }

    @Test
    fun theLanguageAlreadyInForceIsPinnedAboveTheLibrary() {
        // If the catalog lookup fails or the library has no Dutch yet, the
        // choice the viewer already made must not sink into the alphabet.
        val options = LanguageOptions.optionsPrioritising(
            unsetLabel = "Off",
            libraryLanguages = listOf("en"),
            current = "nl",
        )

        assertEquals(listOf("Dutch", "English"), options.drop(1).take(2).map { it.second })
    }

    @Test
    fun withNoSignalTheListIsAlphabeticalRatherThanEditorial() {
        // Thirty-seven entries in prominence order is unscannable; with
        // nothing to prioritise, predictable ordering is the only help left.
        val options = LanguageOptions.optionsPrioritising(unsetLabel = "Off", libraryLanguages = emptyList())
        val labels = options.drop(1).map { it.second }

        assertEquals(labels.sorted(), labels)
        assertEquals(LanguageOptions.tags.size + 1, options.size)
    }

    @Test
    fun libraryLanguagesTheUiCannotOfferAreIgnoredNotInvented() {
        // The catalog holds languages with no picker entry (Catalan, Klingon);
        // they must not appear as unlabelled rows.
        val options = LanguageOptions.optionsPrioritising(
            unsetLabel = "Off",
            libraryLanguages = listOf("cat", "tlh", "nl"),
        )

        assertEquals(LanguageOptions.tags.size + 1, options.size)
        assertEquals("Dutch", options[1].second)
    }

    @Test
    fun everyOptionRemainsUniquelyReversibleWhenPrioritised() {
        // The phone pickers select by label and map back with wireValue, so a
        // reordering that duplicated or dropped a label would misroute writes.
        val options = LanguageOptions.optionsPrioritising(
            unsetLabel = "Off",
            libraryLanguages = listOf("nl", "en"),
            current = "de",
        )
        val offered = options.drop(1)

        assertEquals(offered.size, offered.map { it.second }.toSet().size)
        for ((wire, label) in offered) {
            assertEquals(wire, LanguageOptions.wireValue(label), "label $label")
        }
    }
}
