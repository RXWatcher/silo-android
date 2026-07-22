package org.siloserver.silo.common.diagnostics

fun interface DiagnosticsPlaybackSessionRecorder {
    fun record(sessionId: String)

    data object None : DiagnosticsPlaybackSessionRecorder {
        override fun record(sessionId: String) = Unit
    }
}

class DiagnosticsPlaybackSessionTracker(
    private val maxSessions: Int = 20,
    private val maxIdChars: Int = 128,
) : DiagnosticsPlaybackSessionRecorder {
    private val sessions = ArrayDeque<String>()

    init {
        require(maxSessions > 0)
        require(maxIdChars > 0)
    }

    @Synchronized
    override fun record(sessionId: String) {
        val bounded = sessionId.trim().take(maxIdChars).takeIf(SAFE_ID::matches) ?: return
        sessions.remove(bounded)
        sessions.addLast(bounded)
        while (sessions.size > maxSessions) sessions.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<String> = sessions.toList()

    @Synchronized
    fun clear() = sessions.clear()

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9._:-]+")
    }
}
