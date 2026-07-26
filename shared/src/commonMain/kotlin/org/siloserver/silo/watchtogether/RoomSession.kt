package org.siloserver.silo.watchtogether

import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.repository.WatchTogetherRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the room websocket for as long as the viewer is in a room, independent
 * of whichever screen happens to be composed.
 *
 * Two problems this exists to solve.
 *
 * The connection used to live in screen scope — the lobby's `viewModelScope`
 * and, separately, the player's controller scope. Leaving the lobby therefore
 * closed the socket, the server saw the host disconnect, and the room was
 * closed for everyone shortly after. That makes "stay in a room while you
 * browse for something to suggest" impossible, which is exactly what a vote
 * room needs its members to do.
 *
 * And because *both* of those scopes called `connect`, navigating lobby →
 * player ran two reconnect loops racing over the repository's single `realtime`
 * field. One owner removes that by construction rather than by timing.
 *
 * [scope] must outlive any screen — an application/session scope. The session
 * ends only on an explicit [leave], or when the server closes the room.
 */
class RoomSession(
    private val repository: WatchTogetherRepository,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var job: Job? = null
    private var connectedRoomId: String? = null

    /** The live room, or null when not in one. */
    val room: StateFlow<RoomSnapshot?> = repository.roomSnapshot

    /** Terminal close reason from the server (host left, room closed). */
    val closedReason: StateFlow<String?> = repository.roomClosedReason

    /**
     * Start (or adopt) the connection for [roomId].
     *
     * Idempotent: re-entering a room you are already in is a no-op rather than
     * a second socket. Switching rooms tears the old one down first.
     */
    suspend fun enter(roomId: String) {
        if (roomId.isBlank()) return
        mutex.withLock {
            if (connectedRoomId == roomId && job?.isActive == true) return
            job?.cancel()
            connectedRoomId = roomId
            job = scope.launch { repository.connect(roomId) }
        }
    }

    /**
     * Leave the room and drop the connection.
     *
     * Deliberately NOT called when a screen leaves composition — that is the
     * behaviour this class exists to remove.
     */
    suspend fun leave() {
        mutex.withLock {
            job?.cancel()
            job = null
            connectedRoomId = null
            repository.reset()
        }
    }

    /** Whether a room is currently held open. */
    suspend fun isActive(): Boolean = mutex.withLock { job?.isActive == true }
}
