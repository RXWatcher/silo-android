package org.siloserver.silo.tv.ui.screens.home

import androidx.compose.runtime.saveable.Saver

internal class HomeResumeRefreshPolicy private constructor(
    private var hasResumed: Boolean,
) {
    constructor() : this(hasResumed = false)

    fun shouldRefresh(eligible: Boolean): Boolean {
        if (!hasResumed) {
            hasResumed = true
            return false
        }
        return eligible
    }

    companion object {
        val Saver = Saver<HomeResumeRefreshPolicy, Boolean>(
            save = { policy -> policy.hasResumed },
            restore = { hasResumed -> HomeResumeRefreshPolicy(hasResumed) },
        )
    }
}
