package org.siloserver.silo.model.audiobook

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Bookmarks written before the coordinate space and source file existed are on
 * real devices right now, so how they decode is behaviour, not detail.
 */
class AudiobookBookmarkSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun aRowWrittenBeforeTheNewFieldsDecodesAsUnconvertedWithNoSource() {
        val stored =
            """{"id":"local-1","positionSeconds":150.0,"chapterTitle":"Five","createdAtMs":1}"""

        val bookmark = json.decodeFromString<AudiobookBookmark>(stored)

        assertEquals(150.0, bookmark.positionSeconds)
        // Not WholeBook: 150.0 may be 150s into the book or into a downloaded
        // part, and nothing stored says which.
        assertEquals(BookmarkPositionSpace.AsRecorded, bookmark.positionSpace)
        assertNull(bookmark.sourceFileId)
    }

    @Test
    fun aConvertedBookmarkRoundTripsWithItsSpaceAndSource() {
        val bookmark = AudiobookBookmark(
            id = "local-2",
            positionSeconds = 300.0,
            positionSpace = BookmarkPositionSpace.WholeBook,
            sourceFileId = 11,
            createdAtMs = 2,
        )

        val encoded = json.encodeToString(bookmark)

        assertTrue(encoded.contains("\"position_space\":\"whole_book\""), encoded)
        assertTrue(encoded.contains("\"source_file_id\":11"), encoded)
        assertEquals(bookmark, json.decodeFromString<AudiobookBookmark>(encoded))
    }
}
