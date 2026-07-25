package org.siloserver.silo.tv

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvAndroidManifestPolicyTest {
    private val manifest = File("src/androidMain/AndroidManifest.xml").readText()
    private val buildFile = File("build.gradle.kts").readText()

    @Test
    fun tvDisablesAndroidBackupForTokenAndProfileState() {
        assertTrue(manifest.contains("""android:allowBackup="false""""))
        assertFalse(manifest.contains("""android:allowBackup="true""""))
    }

    @Test
    fun tvKeepsAndroid7InstallFloor() {
        assertTrue(buildFile.contains("minSdk = 24"))
        assertTrue(buildFile.contains("targetSdk = 35"))
        assertTrue(buildFile.contains("compileSdk = 36"))
    }

    @Test
    fun tvManifestHasNoNativePlayerOverride() {
        assertFalse(manifest.contains("dev.jdtech"))
        assertFalse(manifest.contains("overrideLibrary"))
        assertFalse(manifest.contains("libmpv"))
    }

    @Test
    fun tvManifestKeepsSquareIconAndBannerAsDistinctResources() {
        assertTrue(manifest.contains("""android:icon="@mipmap/ic_launcher""""))
        assertTrue(manifest.contains("""android:banner="@drawable/tv_banner""""))
        assertFalse(manifest.contains("""android:icon="@drawable/tv_banner""""))
    }

    @Test
    fun tvMergedManifestPolicyRejectsLegacyStorageAndPhonePermissions() {
        val permissions = mergedPermissionNames()
        assertFalse(permissions.contains("android.permission.READ_PHONE_STATE"))
        assertFalse(permissions.contains("android.permission.READ_EXTERNAL_STORAGE"))
        assertFalse(permissions.contains("android.permission.WRITE_EXTERNAL_STORAGE"))
    }

    private fun mergedPermissionNames(): Set<String> {
        val mergedManifest = File(
            "build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml",
        )
        assertTrue(mergedManifest.isFile, "Missing merged debug manifest")
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(mergedManifest)
        val result = linkedSetOf<String>()
        val elements = document.getElementsByTagName("*")
        for (index in 0 until elements.length) {
            val element = elements.item(index)
            if (!element.nodeName.substringAfter(':').startsWith("uses-permission")) continue
            element.attributes.getNamedItem("android:name")?.nodeValue?.let(result::add)
        }
        return result
    }
}
