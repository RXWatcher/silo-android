package org.siloserver.silo.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidManifestPolicyTest {
    private val manifest = File("src/androidMain/AndroidManifest.xml").readText()
    private val buildFile = File("build.gradle.kts").readText()
    private val doviManifest =
        File("../android-shared/src/native/dovi/AndroidManifest.xml").readText()

    @Test
    fun mobileDisablesAndroidBackupForTokenAndProfileState() {
        assertTrue(manifest.contains("""android:allowBackup="false""""))
        assertFalse(manifest.contains("""android:allowBackup="true""""))
    }

    @Test
    fun mobileKeepsAndroid7InstallFloor() {
        assertTrue(buildFile.contains("minSdk = 24"))
        assertTrue(buildFile.contains("targetSdk = 35"))
        assertTrue(buildFile.contains("compileSdk = 36"))
    }

    @Test
    fun mobileHasNoRemovedNativePlayerManifestOverride() {
        assertFalse(manifest.contains("dev.jdtech"))
        assertFalse(manifest.contains("overrideLibrary"))
    }

    @Test
    fun mobileMergedManifestPolicyKeepsOnlySdkBoundLegacyWriteAccess() {
        val permissions = mergedPermissions()
        assertFalse(permissions.containsKey("android.permission.READ_PHONE_STATE"))
        assertFalse(permissions.containsKey("android.permission.READ_EXTERNAL_STORAGE"))
        assertEquals(
            listOf("28"),
            permissions.getValue("android.permission.WRITE_EXTERNAL_STORAGE"),
        )
        assertTrue(doviManifest.contains("""android:minSdkVersion="24""""))
        assertTrue(doviManifest.contains("""android:targetSdkVersion="35""""))
    }

    private fun mergedPermissions(): Map<String, List<String?>> {
        val mergedManifest = File(
            "build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml",
        )
        assertTrue(mergedManifest.isFile, "Missing merged debug manifest")
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(mergedManifest)
        val result = linkedMapOf<String, MutableList<String?>>()
        val elements = document.getElementsByTagName("*")
        for (index in 0 until elements.length) {
            val element = elements.item(index)
            if (!element.nodeName.substringAfter(':').startsWith("uses-permission")) continue
            val attributes = element.attributes
            val name = attributes.getNamedItem("android:name")?.nodeValue ?: continue
            result.getOrPut(name) { mutableListOf() }
                .add(attributes.getNamedItem("android:maxSdkVersion")?.nodeValue)
        }
        return result
    }
}
