package org.siloserver.silo.android

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedStartupWarmupSourceTest {
    private val source = File(
        "src/androidMain/kotlin/org/siloserver/silo/android/MainActivity.kt",
    ).readText()

    @Test
    fun mobileUsesSharedStartupWarmupWithoutLocalSectionHydration() {
        assertTrue(source.contains("warmAuthenticatedStartup("))
        assertFalse(source.contains("getHomeSectionItems("))
    }
}
