package org.siloserver.silo.catalog

import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.CatalogResponse
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.network.ApiResult
import kotlin.test.*

class LibraryMediaScopeTest {
    private val movies = (1..20).map { SectionItem("film-$it", "movie", "Film $it") }

    /** Recently Added/Released report `total_count = len(items)` after `LIMIT item_limit`; a full slice proves nothing. */
    @Test fun fullInlineSliceRefillsEvenWhenTotalCountEqualsItsSize() = runTest {
        val row = ResolvedSection("recent", "recently_added", "Recent", itemLimit = 20, totalCount = 20, items = movies)
        var loads = 0
        val result = scopeLibrarySection(row, "series") {
            loads++
            ApiResult.Success(CatalogResponse(items = movies.map { BrowseItem(it.contentId, it.type, it.title) } +
                BrowseItem("show", "series", "Show")))
        }
        assertEquals(1, loads)
        assertEquals(listOf("show"), result.section.items.map { it.contentId })
        assertFalse(result.incomplete)
    }

    @Test fun underfilledInlineSliceIsExhaustedWithoutRefill() = runTest {
        val row = ResolvedSection("recent", "recently_added", "Recent", itemLimit = 20, totalCount = 2,
            items = listOf(SectionItem("film", "movie", "Film"), SectionItem("show", "series", "Show")))
        val result = scopeLibrarySection(row, "series") { error("An under-filled slice is the whole shelf") }
        assertEquals(listOf("show"), result.section.items.map { it.contentId })
        assertFalse(result.incomplete)
    }

    /** The catalog section source reads the stored admin definition, so a profile override would be dropped. */
    @Test fun profileCustomizedShelfNeverRefillsFromTheStoredDefinition() = runTest {
        val row = ResolvedSection("random", "random", "Random", itemLimit = 20, totalCount = 21, customized = true, items = movies)
        val result = scopeLibrarySection(row, "series") {
            ApiResult.Success(CatalogResponse(items = listOf(BrowseItem("show", "series", "Show"))))
        }
        assertEquals(emptyList(), result.section.items)
        assertTrue(result.incomplete, "The scoped slice is unverified, so say so rather than hide it silently")
    }

    @Test fun profileAddedShelfNeverRefillsFromTheCatalogSource() = runTest {
        val episode = SectionItem("episode", "episode", "Episode")
        val row = ResolvedSection("mine", "random", "Mine", itemLimit = 20, totalCount = 21, isCustom = true, items = movies.take(19) + episode)
        val result = scopeLibrarySection(row, "series") { error("Profile-added sections have no stored catalog definition") }
        assertEquals(listOf(episode), result.section.items)
        assertTrue(result.incomplete)
    }

    @Test fun customizedShelfAlreadyFilledByItsScopeIsComplete() = runTest {
        val row = ResolvedSection("random", "random", "Random", itemLimit = 20, totalCount = 21, customized = true, items = movies)
        val result = scopeLibrarySection(row, "movie") { error("A filled scoped shelf needs no refill") }
        assertEquals(movies, result.section.items)
        assertFalse(result.incomplete)
    }
}
