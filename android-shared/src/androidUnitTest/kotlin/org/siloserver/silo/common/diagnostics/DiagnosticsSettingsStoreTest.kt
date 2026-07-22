package org.siloserver.silo.common.diagnostics

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsSettingsStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val bindingA = DiagnosticsBinding("server-a", "user-1")
    private val bindingB = DiagnosticsBinding("server-b", "user-1")

    @Test
    fun defaultsToAskAndNoticeBumpDemotesAlwaysButNotNever() = runTest {
        val store = newStore()

        assertEquals(DiagnosticsConsentMode.ASK, store.consent(bindingA, currentNoticeVersion = 1).mode)
        assertFalse(store.debugLogging(bindingA))

        store.setConsent(bindingA, DiagnosticsConsentMode.ALWAYS, noticeVersion = 1)
        assertEquals(DiagnosticsConsentMode.ALWAYS, store.consent(bindingA, 1).mode)
        assertEquals(DiagnosticsConsentMode.ASK, store.consent(bindingA, 2).mode)

        store.setConsent(bindingA, DiagnosticsConsentMode.NEVER, noticeVersion = 2)
        assertEquals(DiagnosticsConsentMode.NEVER, store.consent(bindingA, 3).mode)
    }

    @Test
    fun settingsAreIsolatedByServerAndAccountBinding() = runTest {
        val store = newStore()
        store.setConsent(bindingA, DiagnosticsConsentMode.ALWAYS, noticeVersion = 4)
        store.setDebugLogging(bindingA, enabled = true)

        assertEquals(DiagnosticsConsentMode.ALWAYS, store.consent(bindingA, 4).mode)
        assertTrue(store.debugLogging(bindingA))
        assertEquals(DiagnosticsConsentMode.ASK, store.consent(bindingB, 4).mode)
        assertFalse(store.debugLogging(bindingB))
    }

    @Test
    fun selectingNeverAndExplicitPurgeDeleteBindingEvidenceAndSettings() = runTest {
        val purger = RecordingBindingPurger()
        val store = newStore(purger)
        store.setDebugLogging(bindingA, enabled = true)

        store.setConsent(bindingA, DiagnosticsConsentMode.NEVER, noticeVersion = 1)
        assertEquals(listOf(bindingA), purger.bindings)
        assertFalse(store.debugLogging(bindingA))

        store.setConsent(bindingA, DiagnosticsConsentMode.ALWAYS, noticeVersion = 1)
        store.purgeBinding(bindingA)
        assertEquals(listOf(bindingA, bindingA), purger.bindings)
        assertEquals(DiagnosticsConsentMode.ASK, store.consent(bindingA, 1).mode)
    }

    @Test
    fun sentHistoryIsNewestFirstAndBoundedPerBinding() = runTest {
        val store = newStore(historyLimit = 3)
        repeat(5) { index -> store.recordSent(bindingA, "R-$index", sentAtEpochMs = index.toLong()) }
        store.recordSent(bindingB, "other", sentAtEpochMs = 10)

        assertEquals(listOf("R-4", "R-3", "R-2"), store.sentHistory(bindingA).map { it.shortId })
        assertEquals(listOf("other"), store.sentHistory(bindingB).map { it.shortId })
    }

    private fun newStore(
        purger: DiagnosticsBindingPurger = RecordingBindingPurger(),
        historyLimit: Int = 20,
    ): DiagnosticsSettingsStore {
        val scope = TestScope(UnconfinedTestDispatcher())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            temporaryFolder.newFile("diagnostics-${System.nanoTime()}.preferences_pb")
        }
        return DiagnosticsSettingsStore(dataStore, purger, historyLimit)
    }

    private class RecordingBindingPurger : DiagnosticsBindingPurger {
        val bindings = mutableListOf<DiagnosticsBinding>()
        override suspend fun purge(binding: DiagnosticsBinding) {
            bindings += binding
        }
    }
}
