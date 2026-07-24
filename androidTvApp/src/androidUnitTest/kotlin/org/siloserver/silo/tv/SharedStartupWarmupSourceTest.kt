package org.siloserver.silo.tv

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedStartupWarmupSourceTest {
    private val source = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/MainTvActivity.kt",
    ).readText()

    @Test
    fun tvUsesSharedStartupWarmupWithoutLocalSectionHydration() {
        assertTrue(source.contains("warmAuthenticatedStartup("))
        assertFalse(source.contains("getHomeSectionItems("))
    }
}
