package org.siloserver.silo.model.feature

import kotlin.test.Test
import kotlin.test.assertTrue

class ClientSurfacePolicyTest {
    /**
     * Watch Together is reachable again. This pins the switch's current product
     * state rather than a permanent invariant — if the surface is pulled back
     * behind the flag, this test is meant to be flipped with it. What must not
     * happen is one platform being exposed independently of the other; the
     * per-platform source tests hold that line
     * (`ClientSurfaceVisibilitySourceTest` on phone,
     * `TvWatchTogetherSurfaceSourceTest` on TV).
     */
    @Test
    fun watchTogetherIsExposedInTheDetailOverflows() {
        assertTrue(CLIENT_WATCH_TOGETHER_SURFACE_ENABLED)
    }
}
