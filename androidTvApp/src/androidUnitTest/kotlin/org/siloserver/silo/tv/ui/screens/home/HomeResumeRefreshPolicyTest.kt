package org.siloserver.silo.tv.ui.screens.home

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeResumeRefreshPolicyTest {
    @Test
    fun firstResumeIsAlwaysConsumedWithoutRefreshing() {
        val policy = HomeResumeRefreshPolicy()

        assertFalse(policy.shouldRefresh(eligible = true))
    }

    @Test
    fun laterEligibleResumeRefreshes() {
        val policy = HomeResumeRefreshPolicy()
        policy.shouldRefresh(eligible = true)

        assertTrue(policy.shouldRefresh(eligible = true))
    }

    @Test
    fun laterSuppressedResumeDoesNotRefresh() {
        val policy = HomeResumeRefreshPolicy()
        policy.shouldRefresh(eligible = true)

        assertFalse(policy.shouldRefresh(eligible = false))
    }
}
