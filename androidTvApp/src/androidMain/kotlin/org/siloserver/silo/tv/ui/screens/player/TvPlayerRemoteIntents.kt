package org.siloserver.silo.tv.ui.screens.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Latch for a remote track command that arrived before its track could be
 * resolved (the video backend has not attached, or Media3 has not published its
 * tracks yet). The controller already told the phone the command "completed",
 * so the index must survive until a later track snapshot can resolve it.
 *
 * Unlike a plain `MutableStateFlow<Int?>` this latch is BOUNDED and YIELDS:
 *
 *  - bounded: an index that no track snapshot ever resolves is dropped after
 *    [maxResolutionAttempts] retry passes. An unbounded latch is retried on
 *    every `onTracksChanged` for the rest of the session, so a single bad
 *    ordinal keeps re-applying itself forever.
 *  - yields: [dropForLocalSelection] is called when the TV user makes an
 *    explicit pick. A local pick triggers a replan, the replan republishes the
 *    track list, and the retry pass would otherwise re-apply the stale remote
 *    intent right on top of the selection the user just made.
 */
internal class TvRemoteTrackIntentLatch(
    private val maxResolutionAttempts: Int = MAX_RESOLUTION_ATTEMPTS,
) {
    private val _pending = MutableStateFlow<Int?>(null)

    /** The still-unresolved remote ordinal, or `null` when nothing is pending. */
    val pending: StateFlow<Int?> = _pending.asStateFlow()

    private var attempts = 0

    /**
     * Latches [index] because it could not be resolved against the current
     * tracks. Re-latching the same index (the retry pass failed again) keeps the
     * attempt budget running; a NEW index is a new command and starts over.
     */
    fun latch(index: Int) {
        if (_pending.value != index) {
            _pending.value = index
            attempts = 0
        }
    }

    /**
     * Clears the latch once [index] has been applied. compareAndSet so a command
     * arriving during the suspending apply isn't clobbered by this clear.
     */
    fun resolved(index: Int) {
        if (_pending.compareAndSet(index, null)) attempts = 0
    }

    /**
     * The index to re-attempt on this track snapshot, or `null` when nothing is
     * pending or the budget is spent — in which case the intent is dropped, so
     * it can never be retried again.
     */
    fun nextRetry(): Int? {
        val index = _pending.value ?: return null
        if (attempts >= maxResolutionAttempts) {
            clear()
            return null
        }
        attempts++
        return index
    }

    /**
     * Drops any pending remote intent because the local user has just made an
     * explicit selection. Their choice is newer than the remote command and must
     * not be overridden by it.
     */
    fun dropForLocalSelection() {
        clear()
    }

    fun clear() {
        _pending.value = null
        attempts = 0
    }

    private companion object {
        /**
         * Track snapshots an unresolvable intent may survive. A legitimately
         * late track list settles within a couple of `onTracksChanged` passes;
         * anything beyond that is an ordinal this stream simply does not have.
         */
        const val MAX_RESOLUTION_ATTEMPTS = 5
    }
}
