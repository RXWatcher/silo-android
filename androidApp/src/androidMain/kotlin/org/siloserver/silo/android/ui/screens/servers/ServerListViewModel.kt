package org.siloserver.silo.android.ui.screens.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Where to land after a successful server switch. The list view emits this
 * once the new server's tokens are loaded so the navigator can route to the
 * deepest screen the credentials allow — Login only when the target server
 * has no stored auth.
 */
enum class ServerSwitchDestination { Home, ProfileSelection, Login }

data class ServerListUiState(
    val servers: List<ServerEntry> = emptyList(),
    val activeId: String? = null,
    val pendingSwitchToId: String? = null,
    val switchedTo: ServerSwitchDestination? = null,
    /** One-shot: the last server was removed, so there is nothing to sign into. */
    val needsServerSetup: Boolean = false,
)

/**
 * Drives [ServerListScreen]. Owns no auth state itself — every action
 * round-trips through [ServerRegistry] / [TokenManager].
 *
 * Server entries are surfaced sorted with the active one first, then
 * most-recently-used; the screen renders this list directly.
 */
class ServerListViewModel(
    private val serverRegistry: ServerRegistry,
    private val tokenManager: TokenManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerListUiState())
    val uiState: StateFlow<ServerListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                serverRegistry.entries,
                serverRegistry.activeServerId,
            ) { entries, activeId ->
                val sorted = entries.sortedWith(
                    compareByDescending<ServerEntry> { it.id == activeId }
                        .thenByDescending { it.lastUsedAtEpochMs },
                )
                sorted to activeId
            }.collect { (sorted, activeId) ->
                _uiState.update { it.copy(servers = sorted, activeId = activeId) }
            }
        }
    }

    fun onSelect(serverId: String) {
        if (_uiState.value.activeId == serverId) {
            // Already active — nothing to do, don't fire a navigation event.
            return
        }
        _uiState.update { it.copy(pendingSwitchToId = serverId) }
        viewModelScope.launch {
            serverRegistry.switchTo(serverId)
            // Force the token manager to flush its cache and reload from the
            // new server's slot before the navigator advances. Without this,
            // the destination screen could read stale tokens for one frame.
            tokenManager.switchActiveServer(serverId)

            // Route to the deepest screen the new server's stored credentials
            // can populate. Mirrors MainActivity.resolveStartDestination so a
            // user with valid tokens for the target server skips Login + the
            // profile picker entirely.
            val accessToken = tokenManager.getAccessToken()
            val activeEntry = serverRegistry.activeEntry.value
            val profileId = activeEntry?.profileId ?: tokenManager.getProfileId()
            val destination = when {
                accessToken.isNullOrBlank() -> ServerSwitchDestination.Login
                profileId.isNullOrBlank() -> ServerSwitchDestination.ProfileSelection
                else -> ServerSwitchDestination.Home
            }

            _uiState.update {
                it.copy(pendingSwitchToId = null, switchedTo = destination)
            }
        }
    }

    fun onSwitchConsumed() {
        _uiState.update { it.copy(switchedTo = null) }
    }

    fun onRename(serverId: String, newName: String) {
        viewModelScope.launch {
            serverRegistry.rename(serverId, newName.takeIf { it.isNotBlank() })
        }
    }

    fun onRemove(serverId: String) {
        viewModelScope.launch {
            val wasActive = serverRegistry.activeServerId.value == serverId
            serverRegistry.remove(serverId)

            // Removing a *non-active* server is a passive list edit — the shell
            // behind us is unaffected, so leave navigation exactly as before.
            if (!wasActive) return@launch

            // Removing the ACTIVE server is a session change. The registry has
            // just promoted the next-MRU server and wiped the removed server's
            // tokens, but the Home shell behind this list is still bound to the
            // removed one — so it silently starts showing the promoted account's
            // library, and any progress or favourite write lands on THAT
            // account. Re-resolve like onSelect so the navigator tears it down.
            val promotedId = serverRegistry.activeServerId.value
            if (promotedId == null) {
                _uiState.update { it.copy(needsServerSetup = true) }
                return@launch
            }

            tokenManager.switchActiveServer(promotedId)

            // Land on the deepest screen the promoted server's stored
            // credentials can reach.
            val accessToken = tokenManager.getAccessToken()
            val activeEntry = serverRegistry.activeEntry.value
            val profileId = activeEntry?.profileId ?: tokenManager.getProfileId()
            val destination = when {
                accessToken.isNullOrBlank() -> ServerSwitchDestination.Login
                profileId.isNullOrBlank() -> ServerSwitchDestination.ProfileSelection
                else -> ServerSwitchDestination.Home
            }

            _uiState.update { it.copy(switchedTo = destination) }
        }
    }

    /** Consumes the [ServerListUiState.needsServerSetup] one-shot. */
    fun onServerSetupHandled() {
        _uiState.update { it.copy(needsServerSetup = false) }
    }
}
