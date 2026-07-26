package org.siloserver.silo.android.ui.screens.watchtogether

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.model.watchtogether.AddSuggestionRequest
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.errorMessage
import org.siloserver.silo.repository.WatchTogetherRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Adds the title being viewed to the room the viewer is already in.
 *
 * Mobile counterpart of the TV `TvSuggestToRoomViewModel`; the logic is the
 * same because the gap was the same on both platforms — `addSuggestion` existed
 * on the repository and the API and was called from nowhere, so a vote room
 * could never acquire candidates to vote on.
 *
 * Suggesting is anchored to item detail rather than the lobby: you choose
 * something to suggest by browsing for it, and the lobby has no catalogue in
 * it. The room comes from the repository's live snapshot, which now outlives
 * any single screen, so the action is available wherever you wander while a
 * room is open.
 */
class SuggestToRoomViewModel(
    private val repository: WatchTogetherRepository,
) : ViewModel() {

    data class UiState(
        val isBusy: Boolean = false,
        /** One-shot, consumed by the screen once it has been shown. */
        val notice: String? = null,
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** The room this viewer is currently in, or null. */
    val room: StateFlow<RoomSnapshot?> = repository.roomSnapshot

    fun suggest(
        contentId: String,
        contentType: String,
        title: String,
        subtitle: String?,
        posterUrl: String?,
    ) {
        if (_uiState.value.isBusy) return
        _uiState.update { it.copy(isBusy = true, error = null, notice = null) }
        viewModelScope.launch {
            when (val result = repository.addSuggestion(
                AddSuggestionRequest(
                    contentId = contentId,
                    contentType = contentType,
                    title = title,
                    subtitle = subtitle,
                    posterUrl = posterUrl,
                ),
            )) {
                is ApiResult.Success ->
                    _uiState.update { it.copy(isBusy = false, notice = "Suggested to the room") }
                is ApiResult.Error, is ApiResult.NetworkError ->
                    _uiState.update {
                        it.copy(isBusy = false, error = result.errorMessage("Could not suggest that"))
                    }
            }
        }
    }

    fun consumeNotice() = _uiState.update { it.copy(notice = null) }
    fun clearError() = _uiState.update { it.copy(error = null) }
}
