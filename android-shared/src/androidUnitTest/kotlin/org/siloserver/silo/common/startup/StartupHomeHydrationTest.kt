package org.siloserver.silo.common.startup

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.section.HomeSectionItemsResponse
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.network.ApiResult

class StartupHomeHydrationTest {
    @Test
    fun inlineAndEmptySectionsDoNotTriggerFallback() = runTest {
        val calls = mutableListOf<String>()
        val result = hydrateStartupHomeSections(
            sections = listOf(
                section("inline", totalCount = 1, items = listOf(item("one"))),
                section("empty", totalCount = 0),
            ),
            fetchItems = { id ->
                calls += id
                ApiResult.Error(500, "unexpected", "unexpected fallback")
            },
        )

        assertTrue(result.fullyResolved)
        assertEquals(emptyList(), calls)
        assertEquals(listOf("inline"), result.sections.map { it.id })
    }

    @Test
    fun fallbackSupportsBothResponseShapesAndPreservesOrder() = runTest {
        val result = hydrateStartupHomeSections(
            sections = listOf(
                section("nested", totalCount = 1),
                section("inline", totalCount = 1, items = listOf(item("inline-item"))),
                section("top-level", totalCount = 1),
            ),
            fetchItems = { id ->
                when (id) {
                    "nested" -> ApiResult.Success(
                        HomeSectionItemsResponse(
                            section = section(
                                "nested",
                                totalCount = 1,
                                items = listOf(item("nested-item")),
                            ),
                        ),
                    )
                    "top-level" -> ApiResult.Success(
                        HomeSectionItemsResponse(items = listOf(item("top-level-item"))),
                    )
                    else -> error("unexpected fallback for $id")
                }
            },
        )

        assertTrue(result.fullyResolved)
        assertEquals(listOf("nested", "inline", "top-level"), result.sections.map { it.id })
        assertEquals("top-level-item", result.sections.last().items.single().contentId)
    }

    @Test
    fun fallbackConcurrencyIsBoundedToFour() = runTest {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val result = hydrateStartupHomeSections(
            sections = (1..12).map { section("section-$it", totalCount = 1) },
            fetchItems = { id ->
                val now = active.incrementAndGet()
                peak.updateAndGet { previous -> maxOf(previous, now) }
                delay(10)
                active.decrementAndGet()
                ApiResult.Success(HomeSectionItemsResponse(items = listOf(item("item-$id"))))
            },
        )

        assertTrue(result.fullyResolved)
        assertEquals(4, peak.get())
    }

    @Test
    fun failedFallbackMarksResultPartial() = runTest {
        val result = hydrateStartupHomeSections(
            sections = listOf(
                section("good", totalCount = 1, items = listOf(item("good-item"))),
                section("failed", totalCount = 1),
            ),
            fetchItems = { ApiResult.NetworkError(IllegalStateException("offline")) },
        )

        assertFalse(result.fullyResolved)
        assertEquals(listOf("good"), result.sections.map { it.id })
    }

    private fun section(
        id: String,
        totalCount: Int,
        items: List<SectionItem> = emptyList(),
    ) = ResolvedSection(
        id = id,
        sectionType = "test",
        title = id,
        totalCount = totalCount,
        items = items,
    )

    private fun item(id: String) = SectionItem(contentId = id, type = "movie", title = id)
}
