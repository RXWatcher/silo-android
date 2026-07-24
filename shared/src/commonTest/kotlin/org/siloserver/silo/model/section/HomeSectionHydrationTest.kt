package org.siloserver.silo.model.section

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeSectionHydrationTest {
    @Test
    fun topLevelItemsHydrateSectionMetadataWithDefaultZeroCount() {
        val original = section("original", totalCount = 1)
        val metadata = section("server", totalCount = 0)
        val item = SectionItem(contentId = "movie-1", type = "movie", title = "Movie")

        val resolved = resolveHomeSectionItems(
            originalSection = original,
            response = HomeSectionItemsResponse(section = metadata, items = listOf(item)),
        )

        assertEquals("server", resolved?.id)
        assertEquals(listOf("movie-1"), resolved?.items?.map { it.contentId })
    }

    private fun section(id: String, totalCount: Int) = ResolvedSection(
        id = id,
        sectionType = "test",
        title = id,
        totalCount = totalCount,
    )
}
