package org.siloserver.silo.common.startup

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupWarmupSourceTest {
    private val source = File(
        "src/androidMain/kotlin/org/siloserver/silo/common/startup/StartupWarmup.kt",
    ).readText()

    @Test
    fun warmupUsesBoundedHydrationInsteadOfUnconditionalSectionFanout() {
        assertTrue(source.contains("hydrateStartupHomeSections(result.data.sections)"))
        assertTrue(source.contains("if (resolution.fullyResolved && resolution.sections.isNotEmpty())"))
        assertFalse(source.contains("result.data.sections.map { section ->\n                    async"))
    }
}
