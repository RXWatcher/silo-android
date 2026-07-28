package org.siloserver.silo.android.ui.screens.watchtogether

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.android.ui.navigation.Route
import org.siloserver.silo.model.watchtogether.PromoteSuggestionRequest
import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.Suggestion
import org.siloserver.silo.repository.WatchTogetherRepository
import org.siloserver.silo.watchtogether.RoomSession
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Pure: should the lobby jump to the synced player? Only once the room is
 * actually playing AND a title is selected. Returns the player route or null.
 *
 * Compares against the real [RoomPhase] enum (not a wire string) — the landed
 * shared model uses lenient enums, see WatchTogetherModels.kt.
 */
fun lobbyPlayerDestinationOrNull(room: RoomSnapshot): String? =
    if (room.phase == RoomPhase.Playing &&
        !room.selectedContentId.isNullOrBlank() &&
        // A host alone holds on the invite code — see the TV lobby's
        // shouldEnterSyncedPlayer for why.
        !(room.selfRole == MemberRole.Host && room.memberCount <= 1)
    ) {
        Route.Player(
            contentId = room.selectedContentId!!,
            fileId = room.selectedFileId,
            roomId = room.roomId,
        ).route
    } else {
        null
    }

/**
 * Backs [WatchTogetherLobbyScreen]. Binds the per-room websocket on enter so
 * snapshots/suggestions flow into the state flows, and exposes vote / unvote /
 * host-promote / host-pick / close-room ops.
 *
 * The repository owns the room JWT and reads the active roomId from its own
 * snapshot, so the room-scoped ops take only request/id params (not a roomId).
 * [connect] is suspend and runs the reconnect-with-backoff loop until the
 * scope is cancelled, so it is launched in [viewModelScope].
 */
class WatchTogetherLobbyViewModel(
    private val roomId: String,
    private val repository: WatchTogetherRepository,
    private val roomSession: RoomSession,
) : ViewModel() {

    init {
        // The repo owns reconnect/backoff; we just bind on enter. connect()
        // suspends for the lifetime of the socket, so launch it (don't call bare).
        viewModelScope.launch { roomSession.enter(roomId) }
        // The server pushes only a snapshot at connect; suggestions_update is
        // broadcast on mutation. A member entering (or re-entering) the lobby
        // therefore saw an EMPTY list until someone else next touched a
        // suggestion — which guts a vote room for late joiners. Fetch the
        // current list over REST on entry; failures are tolerated (the next
        // broadcast still lands).
        viewModelScope.launch { repository.refreshSuggestions() }
    }

    val room: StateFlow<RoomSnapshot?> = repository.roomSnapshot
        .stateIn(viewModelScope, SharingStarted.Eagerly, repository.roomSnapshot.value)
    val suggestions: StateFlow<List<Suggestion>> = repository.suggestions
        .stateIn(viewModelScope, SharingStarted.Eagerly, repository.suggestions.value)
    val roomClosedReason: StateFlow<String?> = repository.roomClosedReason
        .stateIn(viewModelScope, SharingStarted.Eagerly, repository.roomClosedReason.value)

    /**
     * Transient, non-terminal server errors (a rejected vote/promote, a refused
     * transport request). They used to flow only to the player screens; in the
     * lobby — where the rejections actually happen — they went nowhere. A
     * SharedFlow passthrough (not a StateFlow) so an identical message repeated
     * still re-fires the toast.
     */
    val errors: kotlinx.coroutines.flow.SharedFlow<String> = repository.errors

    fun vote(suggestionId: String) = viewModelScope.launch { repository.vote(suggestionId) }
    fun unvote(suggestionId: String) = viewModelScope.launch { repository.unvote(suggestionId) }
    fun removeSuggestion(suggestionId: String) =
        viewModelScope.launch { repository.deleteSuggestion(suggestionId) }

    /** Host: promote a suggestion to the room selection (moves everyone to the player). */
    fun promote(suggestionId: String) =
        viewModelScope.launch { repository.promoteSuggestion(PromoteSuggestionRequest(suggestionId = suggestionId)) }

    fun closeRoom() = viewModelScope.launch { repository.closeRoom() }

    /** Guest/host leave: tear down the WS + clear room state. */
    fun leave() {
        // Explicit departure is the only thing that drops the connection now;
        // leaving composition must not.
        viewModelScope.launch { roomSession.leave() }
    }

    override fun onCleared() {
        super.onCleared()
        // Do NOT reset here — the player binding reuses the same repo connection
        // when we auto-navigate into the synced player. reset() is called
        // explicitly via leave() when the user backs out without playing.
    }
}
