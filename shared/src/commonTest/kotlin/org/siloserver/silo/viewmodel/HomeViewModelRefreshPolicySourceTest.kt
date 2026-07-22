package org.siloserver.silo.viewmodel

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class HomeViewModelRefreshPolicySourceTest {
    private val source = File(
        "src/commonMain/kotlin/org/siloserver/silo/viewmodel/HomeViewModel.kt",
    ).readText()

    @Test
    fun repositoryFetchReceivesTheRequestedRefreshPolicy() {
        assertTrue(source.contains("sectionRepository.getHomeSections(forceRefresh = forceRefresh)"))
    }

    @Test
    fun resumeAndUserRefreshForceWhileRealtimeAndInitialLoadStayNormal() {
        assertTrue(source.contains("fun refreshAfterResume()"))
        assertTrue(source.contains("quietRefresh(forceRefresh = true)"))
        assertTrue(source.contains("quietRefresh(forceRefresh = false)"))
        assertTrue(source.contains("fetchSections(forceRefresh = true)"))
        assertTrue(source.contains("fetchSections()"))
    }
}
