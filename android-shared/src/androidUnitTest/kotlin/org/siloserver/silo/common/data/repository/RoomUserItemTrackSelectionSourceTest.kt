package org.siloserver.silo.common.data.repository

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomUserItemTrackSelectionSourceTest {
    @Test
    fun logicalTrackSelectionWritesAudioAndSubtitleAtomically() {
        val port = File(
            "../shared/src/commonMain/kotlin/org/siloserver/silo/repository/port/UserItemStatePort.kt",
        ).readText()
        val repository = File(
            "src/androidMain/kotlin/org/siloserver/silo/common/data/repository/RoomUserItemStateRepository.kt",
        ).readText()

        assertTrue(port.contains("suspend fun recordTrackSelection("))
        val body = repository
            .substringAfter("override suspend fun recordTrackSelection(")
            .substringBefore("override suspend fun localTrackSelection(")
        assertTrue(body.contains("db.withTransaction"))
        assertTrue(body.contains("audioFingerprint ="))
        assertTrue(body.contains("subtitleFingerprint ="))
        assertEquals(
            1,
            Regex("""userStateDao\.upsert\(""").findAll(body).count(),
            "One logical selection must publish one Room row, never two partial writes.",
        )
    }
}
