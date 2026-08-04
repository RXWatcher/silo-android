package org.siloserver.silo.common.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Call-site wiring guard, nothing more. The conversion itself is behavior and
 * is tested as behavior in `AudiobookWholeBookProgressTest`; this only pins
 * that both whole-book sinks — the periodic/pause/seek snapshot and player
 * teardown — actually route through it instead of writing raw part-local state.
 *
 * A source guard is used because the sinks live inside a ViewModel with no
 * behavioral harness in this module. It cannot prove the math, only the wiring.
 */
class AudiobookWholeBookPersistenceSourceTest {
    private val viewModelSource = File(
        requireNotNull(System.getProperty("user.dir")),
        "src/androidMain/kotlin/org/siloserver/silo/common/player/AudiobookPlayerViewModel.kt",
    ).readText()

    @Test
    fun `both whole-book sinks write converted progress rather than raw state`() {
        val writes = viewModelSource.split("userItemStatePort.recordPosition(").drop(1)
        assertEquals(2, writes.size, "expected the snapshot and teardown sinks")

        writes.forEach { write ->
            val args = write.substringBefore(")")
            assertTrue(
                args.contains("positionSeconds = progress.positionSeconds"),
                "a whole-book write bypassed the conversion: $args",
            )
            assertTrue(
                args.contains("durationSeconds = progress.totalSeconds"),
                "a whole-book write recorded a part total as the book total: $args",
            )
        }

        assertEquals(
            writes.size,
            viewModelSource.split("wholeBookProgressFor(state)?.let").size - 1,
            "every recordPosition site must be gated by wholeBookProgressFor",
        )
    }

    @Test
    fun `each load re-decides the mode and every offline branch names one`() {
        // The mode is one field precisely so no branch can set half of it, but
        // a branch can still forget to set it at all. Deleting the reset would
        // clamp a fresh global position into a previous part's span; deleting
        // an offline assignment would persist raw part-local time as the book's.
        val loadDetail = viewModelSource
            .substringAfter("private fun loadDetail() {")
            .substringBefore("viewModelScope.launch {")
        assertTrue(
            loadDetail.contains("wholeBookMode = WholeBookProgressMode.AlreadyGlobal"),
            "loadDetail must clear the previous decision",
        )

        assertEquals(
            2,
            viewModelSource.split("WholeBookProgressMode.OfflinePart(").size - 1,
            "both mapped-offline branches must declare OfflinePart",
        )
        assertTrue(
            viewModelSource.contains("wholeBookMode = WholeBookProgressMode.Unmappable"),
            "the no-timeline branch must declare Unmappable",
        )
    }

    @Test
    fun `the mode is a single field so no site can express half a decision`() {
        assertEquals(
            1,
            viewModelSource.split("private var wholeBookMode").size - 1,
            "exactly one mode field",
        )
        // The superseded pair must not come back.
        assertTrue(!viewModelSource.contains("suppressWholeBookPersistence"))
        assertTrue(!viewModelSource.contains("offlineWholeBookMapping"))
    }
}
