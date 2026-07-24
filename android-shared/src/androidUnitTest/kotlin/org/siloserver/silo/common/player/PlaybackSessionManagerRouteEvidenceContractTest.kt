package org.siloserver.silo.common.player

import kotlin.test.Test
import kotlin.test.assertTrue

class PlaybackSessionManagerRouteEvidenceContractTest {
    @Test
    fun `staged video replan exposes the manager derived output route generation`() {
        val fields = StagedVideoReplan::class.java.declaredFields.mapTo(mutableSetOf()) { it.name }

        assertTrue(
            "outputRouteGeneration" in fields,
            "The staged manager handle must carry manager-derived route evidence.",
        )
    }
}
