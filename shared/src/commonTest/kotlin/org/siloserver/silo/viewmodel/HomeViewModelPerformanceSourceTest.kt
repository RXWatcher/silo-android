package org.siloserver.silo.viewmodel

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeViewModelPerformanceSourceTest {
    private val source = File(
        "src/commonMain/kotlin/org/siloserver/silo/viewmodel/HomeViewModel.kt",
    ).readText()

    @Test
    fun fallbackHydrationUsesSharedFourRequestConcurrencyCap() {
        assertTrue(source.contains("mapConcurrentBounded(maxConcurrency = 4)"))
        assertFalse(source.contains("needsFetch.map { section ->\n                        viewModelScope.async"))
    }
}
