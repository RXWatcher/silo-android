package org.siloserver.silo.android.ui.screens.detail

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ItemDetailTrackSelectionSourceTest {
    private val source = File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/ItemDetailViewModel.kt",
    ).readText()

    @Test
    fun `audio actions cannot clear a typed subtitle preference`() {
        val audioSelectionBody = source
            .substringAfter("fun selectAudioTrack(index: Int)")
            .substringBefore("fun selectAutoAudioTrack()")
        val audioPersistenceBody = source
            .substringAfter("private fun persistAudioTrackSelection()")
            .substringBefore("private fun persistSubtitleTrackSelection()")

        assertTrue(audioSelectionBody.contains("persistAudioTrackSelection()"))
        assertFalse(audioSelectionBody.contains("persistTrackSelection()"))
        assertTrue(audioPersistenceBody.contains("recordAudioTrackSelection("))
        assertFalse(audioPersistenceBody.contains("recordSubtitleTrackSelection("))
    }

    @Test
    fun `detail subtitle actions write the shared typed format`() {
        val subtitlePersistenceBody = source
            .substringAfter("private fun persistSubtitleTrackSelection()")
            .substringBefore("private fun seedPersistedTrackSelection()")

        assertTrue(subtitlePersistenceBody.contains("encodeCatalogSubtitlePreference("))
        assertTrue(subtitlePersistenceBody.contains("recordSubtitleTrackSelection("))
    }
}
