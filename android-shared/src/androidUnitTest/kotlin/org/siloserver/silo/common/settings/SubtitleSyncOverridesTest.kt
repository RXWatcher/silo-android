package org.siloserver.silo.common.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The per-item override map. Encoding is plain `contentId=ms` lines, so the
 * cases that matter are the ones that would corrupt a neighbouring entry or
 * grow the preference without limit.
 */
class SubtitleSyncOverridesTest {

    @Test
    fun roundTripsEntries() {
        val encoded = encodeSubtitleSyncOverrides(mapOf("movie-1" to -250, "episode-2" to 1_500))

        assertEquals(mapOf("movie-1" to -250, "episode-2" to 1_500), decodeSubtitleSyncOverrides(encoded))
    }

    @Test
    fun emptyInputsAreEmptyMaps() {
        assertEquals(emptyMap<String, Int>(), decodeSubtitleSyncOverrides(""))
        assertEquals(emptyMap<String, Int>(), decodeSubtitleSyncOverrides("   "))
        assertEquals("", encodeSubtitleSyncOverrides(emptyMap()))
    }

    @Test
    fun malformedLinesAreDroppedNotGuessedAt() {
        val decoded = decodeSubtitleSyncOverrides("good=100\nbroken\nalso-broken=notanumber\n=500\n")

        assertEquals(mapOf("good" to 100), decoded)
    }

    // An id carrying a separator would corrupt the entry next to it, so it is
    // dropped rather than escaped — no catalog id looks like that.
    @Test
    fun idsCarryingSeparatorsAreRefused() {
        val encoded = encodeSubtitleSyncOverrides(
            mapOf("ok" to 1, "bad=id" to 2, "bad\nid" to 3, "" to 4),
        )

        assertEquals(mapOf("ok" to 1), decodeSubtitleSyncOverrides(encoded))
    }

    // A long viewing history must not grow this preference without limit.
    @Test
    fun theMapIsBoundedKeepingTheMostRecent() {
        val many = (1..250).associate { "item-$it" to it }

        val decoded = decodeSubtitleSyncOverrides(encodeSubtitleSyncOverrides(many))

        assertEquals(200, decoded.size)
        assertEquals(250, decoded["item-250"])
        assertEquals(null, decoded["item-1"])
    }
}
