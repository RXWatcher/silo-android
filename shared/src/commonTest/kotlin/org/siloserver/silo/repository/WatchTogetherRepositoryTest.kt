package org.siloserver.silo.repository

import org.siloserver.silo.model.watchtogether.AddSuggestionRequest
import org.siloserver.silo.model.watchtogether.CreateRoomRequest
import org.siloserver.silo.model.watchtogether.JoinRoomRequest
import org.siloserver.silo.model.watchtogether.PromoteSuggestionRequest
import org.siloserver.silo.model.watchtogether.RoomResponse
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.SetSelectionRequest
import org.siloserver.silo.model.watchtogether.Suggestion
import org.siloserver.silo.model.watchtogether.SuggestionsResponse
import org.siloserver.silo.model.watchtogether.UpdatePolicyRequest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.RoomRealtimeEvent
import org.siloserver.silo.network.WatchTogetherRealtimeClient
import org.siloserver.silo.network.api.WatchTogetherApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchTogetherRepositoryTest {

    // ---- Fakes ---------------------------------------------------------------

    private fun snapshot(
        roomId: String = "room-1",
        revision: Long = 1L,
        memberCount: Int = 1,
    ) = RoomSnapshot(roomId = roomId, selectionRevision = revision, memberCount = memberCount, code = "ABCD1234")

    private fun suggestion(id: String, voteCount: Int = 0, votedByMe: Boolean = false) = Suggestion(
        id = id, roomId = "room-1", contentId = "c-$id", contentType = "movie",
        title = "T-$id", voteCount = voteCount, votedByMe = votedByMe, createdAt = "2026-06-12T08:00:00Z",
    )

    private class FakeApi(
        var createResponse: ApiResult<RoomResponse> = ApiResult.Success(
            RoomResponse(RoomSnapshot(roomId = "room-1", code = "ABCD1234"), "jwt-room"),
        ),
    ) : WatchTogetherApi {
        var lastRoomToken: String? = null
        var lastSelection: SetSelectionRequest? = null
        override suspend fun createRoom(request: CreateRoomRequest) = createResponse
        override suspend fun joinRoom(request: JoinRoomRequest) = createResponse
        override suspend fun getRoom(roomId: String, roomToken: String) = createResponse.also { lastRoomToken = roomToken }
        override suspend fun setSelection(roomId: String, roomToken: String, request: SetSelectionRequest): ApiResult<RoomResponse> {
            lastRoomToken = roomToken; lastSelection = request; return createResponse
        }
        override suspend fun updatePolicy(roomId: String, roomToken: String, request: UpdatePolicyRequest) =
            createResponse.also { lastRoomToken = roomToken }
        override suspend fun closeRoom(roomId: String, roomToken: String): ApiResult<Unit> {
            lastRoomToken = roomToken; return ApiResult.Success(Unit)
        }
        var suggestionsResponse: ApiResult<SuggestionsResponse> = ApiResult.Success(SuggestionsResponse())
        override suspend fun listSuggestions(roomId: String, roomToken: String) =
            suggestionsResponse.also { lastRoomToken = roomToken }
        override suspend fun addSuggestion(roomId: String, roomToken: String, request: AddSuggestionRequest) =
            ApiResult.Success(SuggestionsResponse()).also { lastRoomToken = roomToken }
        override suspend fun deleteSuggestion(roomId: String, roomToken: String, suggestionId: String) =
            ApiResult.Success(SuggestionsResponse()).also { lastRoomToken = roomToken }
        override suspend fun vote(roomId: String, roomToken: String, suggestionId: String) =
            ApiResult.Success(SuggestionsResponse()).also { lastRoomToken = roomToken }
        override suspend fun unvote(roomId: String, roomToken: String, suggestionId: String) =
            ApiResult.Success(SuggestionsResponse()).also { lastRoomToken = roomToken }
        override suspend fun promoteSuggestion(roomId: String, roomToken: String, request: PromoteSuggestionRequest) =
            createResponse.also { lastRoomToken = roomToken }
    }

    private class FakeRealtime(
        val events: MutableSharedFlow<RoomRealtimeEvent> = MutableSharedFlow(replay = 0, extraBufferCapacity = 64),
    ) : WatchTogetherRealtimeClient {
        var connectCount = 0
        /** When true, the returned flow throws immediately on collection. */
        var failConnect = false
        /**
         * When non-null, each connect attempt emits this one event and then throws.
         * Models a flapping server: healthy traffic is observed but the connection
         * always drops — the classic scenario that could bypass the failure cap if
         * failures were reset per-event rather than per-clean-completion.
         */
        var flappingEvent: RoomRealtimeEvent? = null
        /**
         * Per-attempt scripted flows, consumed in order; when exhausted, falls
         * back to the hot [events] flow. Lets a test model the REAL client's
         * behaviour — a flow that emits Disconnected and then completes — for
         * one attempt, and a healthy connection for the next.
         */
        val scriptedAttempts = ArrayDeque<List<RoomRealtimeEvent>>()
        override fun connect(roomId: String, roomToken: String): Flow<RoomRealtimeEvent> {
            connectCount++
            if (failConnect) return flow { throw IllegalStateException("boom") }
            val flapEvent = flappingEvent
            if (flapEvent != null) return flow {
                emit(flapEvent)
                throw IllegalStateException("connection dropped after event")
            }
            scriptedAttempts.removeFirstOrNull()?.let { scripted ->
                return flow { scripted.forEach { emit(it) } }
            }
            return events.asSharedFlow()
        }
        /** Whether this fake socket is "open" — see [WatchTogetherRealtimeClient]. */
        var delivers: Boolean = true

        override suspend fun attachSession(sessionId: String): Boolean = delivers
        override suspend fun transportRequest(
            action: String,
            positionSeconds: Double?,
            isPaused: Boolean,
        ): Boolean = delivers
        override suspend fun stateReport(
            sessionId: String,
            positionSeconds: Double,
            isPaused: Boolean,
        ): Boolean = delivers
        override suspend fun ready(sessionId: String, positionSeconds: Double, isPaused: Boolean): Boolean =
            delivers
        override suspend fun buffering(
            sessionId: String,
            positionSeconds: Double,
            isPaused: Boolean,
        ): Boolean = delivers
        override suspend fun ping(clientSentAt: String): Boolean = delivers
    }

    private fun repo(
        api: FakeApi = FakeApi(),
        realtime: FakeRealtime = FakeRealtime(),
    ) = WatchTogetherRepository(api = api, realtimeFactory = { realtime })

    // ---- create stores room token -------------------------------------------

    @Test
    fun `create stores room token used by subsequent room-scoped calls`() = runTest {
        val api = FakeApi()
        val r = repo(api = api)
        r.createRoom(CreateRoomRequest(selectionMode = "vote"))
        r.setSelection(SetSelectionRequest(contentId = "tt-9"))
        assertEquals("jwt-room", api.lastRoomToken)
        assertEquals("tt-9", api.lastSelection?.contentId)
    }

    // ---- snapshot fold --------------------------------------------------------

    @Test
    fun `snapshot event publishes room snapshot`() = runTest {
        val realtime = FakeRealtime()
        val r = repo(realtime = realtime)
        r.createRoom(CreateRoomRequest())
        val job = launch { r.connect("room-1") }
        advanceUntilIdle()
        realtime.events.emit(RoomRealtimeEvent.SnapshotEvent(snapshot(revision = 5, memberCount = 3)))
        advanceUntilIdle()
        assertEquals(5L, r.roomSnapshot.value?.selectionRevision)
        assertEquals(3, r.roomSnapshot.value?.memberCount)
        job.cancel()
    }

    // ---- suggestions fold + voted_by_me re-merge ------------------------------

    @Test
    fun `suggestions event re-merges voted_by_me from local vote set`() = runTest {
        val api = FakeApi()
        val realtime = FakeRealtime()
        val r = repo(api = api, realtime = realtime)
        r.createRoom(CreateRoomRequest())
        val job = launch { r.connect("room-1") }
        advanceUntilIdle()

        // Local user voted for s2 (REST round-trip records it in the local set).
        r.vote("s2")
        advanceUntilIdle()

        // Broadcast forces voted_by_me=false for all — repository must re-merge.
        realtime.events.emit(
            RoomRealtimeEvent.SuggestionsEvent(
                listOf(
                    suggestion("s1", voteCount = 1, votedByMe = false),
                    suggestion("s2", voteCount = 2, votedByMe = false),
                ),
            ),
        )
        advanceUntilIdle()

        val byId = r.suggestions.value.associateBy { it.id }
        assertTrue(byId.getValue("s2").votedByMe) // re-merged from local set
        assertTrue(!byId.getValue("s1").votedByMe)
        job.cancel()
    }

    @Test
    fun `unvote removes from local vote set so re-merge keeps false`() = runTest {
        val realtime = FakeRealtime()
        val r = repo(realtime = realtime)
        r.createRoom(CreateRoomRequest())
        val job = launch { r.connect("room-1") }
        advanceUntilIdle()
        r.vote("s2"); advanceUntilIdle()
        r.unvote("s2"); advanceUntilIdle()
        realtime.events.emit(RoomRealtimeEvent.SuggestionsEvent(listOf(suggestion("s2", votedByMe = false))))
        advanceUntilIdle()
        assertTrue(!r.suggestions.value.first().votedByMe)
        job.cancel()
    }

    // ---- reset ---------------------------------------------------------------

    @Test
    fun `reset clears snapshot suggestions and room token`() = runTest {
        val api = FakeApi()
        val realtime = FakeRealtime()
        val r = repo(api = api, realtime = realtime)
        r.createRoom(CreateRoomRequest())
        val job = launch { r.connect("room-1") }
        advanceUntilIdle()
        realtime.events.emit(RoomRealtimeEvent.SnapshotEvent(snapshot()))
        realtime.events.emit(RoomRealtimeEvent.SuggestionsEvent(listOf(suggestion("s1"))))
        advanceUntilIdle()

        r.reset()
        assertNull(r.roomSnapshot.value)
        assertTrue(r.suggestions.value.isEmpty())

        // After reset there is no room, so a room-scoped call must fail fast
        // rather than reach the API at all. It used to send one with an empty
        // room id and an empty token, which came back as a 404/405 the UI
        // reported as a rejected action.
        api.lastRoomToken = null
        val result = r.setSelection(SetSelectionRequest(contentId = "x"))
        assertTrue(result is ApiResult.Error && result.error == "no_active_room")
        assertNull(api.lastRoomToken) // never reached the API
        job.cancel()
    }

    // ---- closed/error surface ------------------------------------------------

    @Test
    fun `room_closed populates roomClosedReason and reset clears it`() = runTest {
        val realtime = FakeRealtime()
        val r = repo(realtime = realtime)
        r.createRoom(CreateRoomRequest())
        val job = launch { r.connect("room-1") }
        advanceUntilIdle()
        assertNull(r.roomClosedReason.value)

        realtime.events.emit(RoomRealtimeEvent.Closed("host_left"))
        advanceUntilIdle()
        assertEquals("host_left", r.roomClosedReason.value)

        r.reset()
        assertNull(r.roomClosedReason.value)
        job.cancel()
    }

    @Test
    fun `error frame surfaces on errors and does not populate roomClosedReason`() = runTest {
        val realtime = FakeRealtime()
        val r = repo(realtime = realtime)
        r.createRoom(CreateRoomRequest())
        val job = launch { r.connect("room-1") }
        advanceUntilIdle()
        assertNull(r.roomClosedReason.value)

        // Collect the transient errors stream; it's a hot SharedFlow so we must
        // be subscribed before the event is emitted.
        val seen = mutableListOf<String>()
        val errorJob = launch { r.errors.collect { seen.add(it) } }
        advanceUntilIdle()

        // A transient server `error` frame (e.g. a rejected transport_request)
        // must NOT eject the user: roomClosedReason stays null and the message
        // surfaces on the errors stream instead.
        realtime.events.emit(RoomRealtimeEvent.Error(code = "rejected", message = "transport rejected"))
        advanceUntilIdle()

        assertNull(r.roomClosedReason.value)
        assertEquals(listOf("transport rejected"), seen)

        errorJob.cancel()
        job.cancel()
    }

    // ---- reconnect stops on room_closed --------------------------------------

    @Test
    fun `reconnect loop stops after room_closed`() = runTest {
        val realtime = FakeRealtime()
        val r = repo(realtime = realtime)
        r.createRoom(CreateRoomRequest())
        val job = launch { r.connect("room-1") }
        advanceUntilIdle()
        assertEquals(1, realtime.connectCount)

        // Server-initiated close: repository must NOT reconnect.
        realtime.events.emit(RoomRealtimeEvent.Closed("host_left"))
        advanceUntilIdle()
        assertEquals(1, realtime.connectCount)
        assertTrue(job.isCompleted || job.isCancelled)
    }

    @Test
    fun `reconnect loop stops after room_closed with no reason`() = runTest {
        val realtime = FakeRealtime()
        val r = repo(realtime = realtime)
        r.createRoom(CreateRoomRequest())
        val job = launch { r.connect("room-1") }
        advanceUntilIdle()
        assertEquals(1, realtime.connectCount)

        // Server-initiated close with no reason: repository must NOT reconnect.
        realtime.events.emit(RoomRealtimeEvent.Closed(null))
        advanceUntilIdle()
        assertEquals(1, realtime.connectCount)
        assertTrue(job.isCompleted || job.isCancelled)
    }

    // ---- reconnect gives up after max consecutive failures ----------------------

    @Test
    fun `reconnect loop gives up after the max consecutive failures`() = runTest {
        val realtime = FakeRealtime().apply { failConnect = true }
        val r = repo(realtime = realtime)
        r.createRoom(CreateRoomRequest())
        val job = launch { r.connect("room-1") }
        advanceUntilIdle()
        // The loop must have stopped on its own (job completed, not still running).
        assertTrue(job.isCompleted || job.isCancelled)
        // And the closed reason must signal connection_lost.
        assertEquals("connection_lost", r.roomClosedReason.value)
        // Verify the exact number of attempts so an off-by-one or wrong-constant
        // regression is caught immediately. Expected: MAX_RECONNECT_ATTEMPTS = 6.
        assertEquals(WatchTogetherRepository.MAX_RECONNECT_ATTEMPTS, realtime.connectCount)
        // Stale room state must not be observable after giving up.
        assertNull(r.roomSnapshot.value)
    }

    // ---- flapping server hits the cap (regression for Issue 1) -----------------

    @Test
    fun `flapping server that emits then drops still hits the reconnect cap`() = runTest {
        // Each connect attempt emits one healthy SnapshotEvent, then throws.
        // Under the buggy code (failures reset per healthy event), this would loop
        // forever because failures never accumulates to MAX_RECONNECT_ATTEMPTS.
        // Under the correct code (failures reset only on clean completion), each
        // throwing attempt still increments failures and the loop terminates.
        val realtime = FakeRealtime().apply {
            flappingEvent = RoomRealtimeEvent.SnapshotEvent(
                RoomSnapshot(roomId = "room-1", code = "ABCD1234"),
            )
        }
        val r = repo(realtime = realtime)
        r.createRoom(CreateRoomRequest())
        val job = launch { r.connect("room-1") }
        advanceUntilIdle()

        assertTrue(job.isCompleted || job.isCancelled)
        assertEquals("connection_lost", r.roomClosedReason.value)
        // Must have stopped after exactly MAX_RECONNECT_ATTEMPTS attempts.
        assertEquals(WatchTogetherRepository.MAX_RECONNECT_ATTEMPTS, realtime.connectCount)
        // Stale room state must not be observable after the cap is hit.
        assertNull(r.roomSnapshot.value)
    }
    // ---- disconnect vs closed (regression for the review's finding 1) ---------

    @Test
    fun `a socket disconnect reconnects instead of ending the room`() = runTest {
        // Attempt 1 behaves like the REAL client on a network blip: one healthy
        // snapshot, then Disconnected, then flow completion. Attempt 2 is the
        // healed connection (falls back to the hot events flow). Under the old
        // code the Disconnected (then Closed) event was terminal: one blip set
        // roomClosedReason, nulled the snapshot, and never reconnected.
        val realtime = FakeRealtime().apply {
            scriptedAttempts.addLast(
                listOf(
                    RoomRealtimeEvent.SnapshotEvent(
                        RoomSnapshot(roomId = "room-1", code = "ABCD1234"),
                    ),
                    RoomRealtimeEvent.Disconnected("connection reset"),
                ),
            )
        }
        val r = repo(realtime = realtime)
        r.createRoom(CreateRoomRequest())
        val job = launch { r.connect("room-1") }
        advanceUntilIdle()

        // Reconnected: a second attempt happened and the room is NOT closed.
        assertTrue(realtime.connectCount >= 2, "expected a reconnect attempt, got ${'$'}{realtime.connectCount}")
        assertNull(r.roomClosedReason.value)

        // The healed connection still folds events.
        realtime.events.emit(RoomRealtimeEvent.SnapshotEvent(snapshot(revision = 9)))
        advanceUntilIdle()
        assertEquals(9L, r.roomSnapshot.value?.selectionRevision)
        job.cancel()
    }

    @Test
    fun `a disconnect keeps the last snapshot while reconnecting`() = runTest {
        // The lobby should keep showing the room while the connection heals;
        // only room_closed / giving up may null the snapshot.
        val realtime = FakeRealtime().apply {
            scriptedAttempts.addLast(
                listOf(
                    RoomRealtimeEvent.SnapshotEvent(snapshot(memberCount = 4)),
                    RoomRealtimeEvent.Disconnected(null),
                ),
            )
        }
        val r = repo(realtime = realtime)
        r.createRoom(CreateRoomRequest())
        val job = launch { r.connect("room-1") }
        advanceUntilIdle()
        assertEquals(4, r.roomSnapshot.value?.memberCount)
        job.cancel()
    }

    @Test
    fun `reconnect refreshes suggestions missed while disconnected`() = runTest {
        // Suggestions are only broadcast on mutation, so anything voted or
        // suggested during the gap is invisible until the next mutation. The
        // loop must re-fetch over REST before re-collecting.
        val api = FakeApi()
        val realtime = FakeRealtime().apply {
            scriptedAttempts.addLast(listOf(RoomRealtimeEvent.Disconnected("blip")))
        }
        val r = repo(api = api, realtime = realtime)
        r.createRoom(CreateRoomRequest())
        api.lastRoomToken = null
        val job = launch { r.connect("room-1") }
        advanceUntilIdle()
        assertTrue(realtime.connectCount >= 2)
        // listSuggestions ran with the room token during the reconnect.
        assertEquals("jwt-room", api.lastRoomToken)
        job.cancel()
    }

    @Test
    fun `repeated disconnects still hit the reconnect cap`() = runTest {
        // Every attempt ends in Disconnected (real-client shape for a dead
        // network). The cap must terminate the loop exactly as it does for
        // throwing attempts — a disconnect is a failure, not a clean end.
        val realtime = FakeRealtime()
        repeat(WatchTogetherRepository.MAX_RECONNECT_ATTEMPTS + 2) {
            realtime.scriptedAttempts.addLast(listOf(RoomRealtimeEvent.Disconnected("down")))
        }
        val r = repo(realtime = realtime)
        r.createRoom(CreateRoomRequest())
        val job = launch { r.connect("room-1") }
        advanceUntilIdle()
        assertTrue(job.isCompleted || job.isCancelled)
        assertEquals("connection_lost", r.roomClosedReason.value)
        assertEquals(WatchTogetherRepository.MAX_RECONNECT_ATTEMPTS, realtime.connectCount)
        assertNull(r.roomSnapshot.value)
    }

    // ---- cross-device unvote unlearns (regression for finding 6) ---------------

    @Test
    fun `a REST list that denies a vote unlearns the stale local vote`() = runTest {
        val api = FakeApi()
        val realtime = FakeRealtime()
        val r = repo(api = api, realtime = realtime)
        r.createRoom(CreateRoomRequest())
        val job = launch { r.connect("room-1") }
        advanceUntilIdle()

        // Voted here...
        r.vote("s2"); advanceUntilIdle()

        // ...then unvoted on ANOTHER device. The next REST refresh carries the
        // authoritative votedByMe=false; the local set must unlearn it so a
        // later broadcast re-merge doesn't resurrect the vote forever.
        api.suggestionsResponse = ApiResult.Success(
            SuggestionsResponse(suggestions = listOf(suggestion("s2", votedByMe = false))),
        )
        r.refreshSuggestions(); advanceUntilIdle()

        realtime.events.emit(
            RoomRealtimeEvent.SuggestionsEvent(listOf(suggestion("s2", votedByMe = false))),
        )
        advanceUntilIdle()
        assertFalse(r.suggestions.value.first().votedByMe)
        job.cancel()
    }

    @Test
    fun `sends report undelivered before the socket is up`() = runTest {
        // There is a real window between launching connect() and the socket
        // existing. Sends in it used to vanish with no signal, which cost the
        // room this member's attach_session for good — the controller latched
        // "already sent" on a frame that never left.
        val r = repo()

        assertFalse(r.attachSession("session-1"))
        assertFalse(r.ping("2026-07-26T12:00:00Z"))
        assertFalse(r.stateReport("session-1", positionSeconds = 10.0, isPaused = false))
    }

    @Test
    fun `sends report delivered once connected`() = runTest {
        val realtime = FakeRealtime()
        val r = repo(realtime = realtime)
        val job = launch { r.connect("room-1") }
        advanceUntilIdle()

        assertTrue(r.attachSession("session-1"))

        // A socket that is up but refuses the write is reported the same way, so
        // a caller with one shot at the frame can tell it has to try again.
        realtime.delivers = false
        assertFalse(r.attachSession("session-1"))

        job.cancel()
    }
}
