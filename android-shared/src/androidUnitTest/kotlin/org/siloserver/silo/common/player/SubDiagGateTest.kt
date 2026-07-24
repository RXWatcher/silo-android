package org.siloserver.silo.common.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SubDiag's call sites interpolate subtitle identities and session ids, and this
 * is a public client — the tracing must be opt-in, and the gate must live inside
 * the helper so it cannot be half-applied across its thirty call sites.
 */
class SubDiagGateTest {

    private val source = File(
        "src/androidMain/kotlin/org/siloserver/silo/common/player/SubtitleDiagnostics.kt",
    ).readText()

    @Test
    fun `every emit is behind the gate`() {
        val emits = Regex("""Log\.[a-z]\(TAG""").findAll(source).count()
        val guards = Regex("""if \(enabled\) Log\.""").findAll(source).count()
        assertTrue(emits > 0, "the helper should still emit something when enabled")
        assertTrue(
            guards >= emits,
            "an ungated Log call ships subtitle identities and session ids to logcat",
        )
    }

    @Test
    fun `tracing does not ship at error level`() {
        // It was Log.e so it would survive release log stripping while the bug
        // was being chased. At error level an enabled build also pollutes crash
        // breadcrumbs with routine mount chatter.
        assertFalse(
            source.contains("Log.e(TAG"),
            "diagnostic tracing must not masquerade as an error",
        )
    }
}
