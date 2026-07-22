package org.siloserver.silo.tv.ui.shell

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class TvCollectionsPresenceCacheTest {
    private val source = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/shell/TvMainShell.kt",
    ).readText()

    @Test
    fun collectionPresenceProbeSurvivesShellRecreationAndSkipsUnchangedIds() {
        assertTrue(source.contains("var probedCollectionLibraryIds by rememberSaveable"))
        assertTrue(source.contains("probedCollectionLibraryIds.contentEquals(currentLibraryIds)"))
        assertTrue(source.contains("var librariesWithCollectionsSnapshot by rememberSaveable"))
        assertTrue(source.contains("var probedCollectionsIdentityKey by rememberSaveable"))
        assertTrue(source.contains("probedCollectionsIdentityKey == collectionsIdentityKey"))
        assertTrue(source.contains("probedCollectionsIdentityKey = collectionsIdentityKey"))
    }
}
