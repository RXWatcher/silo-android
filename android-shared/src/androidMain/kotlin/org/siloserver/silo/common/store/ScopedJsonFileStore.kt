package org.siloserver.silo.common.store

import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared plumbing for the per-(serverId, profileId, contentId) JSON
 * file stores (ebook reading state, audiobook positions/bookmarks):
 * scoped path resolution under a root directory, one lenient [Json]
 * instance, safe reads (missing/corrupt files log and return null),
 * and atomic writes (tmp + fsync + rename) so a crash mid-write can't
 * leave a half-written file that fails to decode next launch.
 */
internal class ScopedJsonFileStore(
    private val root: File,
    internal val tag: String,
) {
    private val canonicalRoot: File = root.canonicalFile
    private val legacyByTarget = ConcurrentHashMap<String, File>()

    internal fun rootDirectory(): File = canonicalRoot

    internal fun fileFor(
        serverId: String,
        profileId: String,
        contentId: String,
        suffix: String = ".json",
    ): File {
        val directory = requireNotNull(containedSafeChild(canonicalRoot, serverId, profileId)) {
            "Scoped JSON directory is outside its root"
        }
        val target = requireNotNull(containedFile(directory, safePathSegment(contentId) + suffix)) {
            "Scoped JSON filename is outside its directory"
        }
        containedLegacyChild(canonicalRoot, serverId, profileId, contentId + suffix)
            ?.takeIf { it.canonicalPath != target.canonicalPath }
            ?.let { legacy -> legacyByTarget[target.canonicalPath] = legacy }
        return target
    }

    internal inline fun <reified T> read(file: File): T? {
        val source = containedReadCandidate(file) ?: return null
        return runCatching { json.decodeFromString<T>(source.readText()) }
            .onFailure { Log.w(tag, "read failed for ${source.path}", it) }
            .getOrNull()
    }

    internal inline fun <reified T> write(target: File, value: T) {
        writeAtomic(target, json.encodeToString(value))
    }

    internal fun writeAtomic(target: File, text: String) {
        if (!isContained(target)) {
            Log.w(tag, "write rejected outside store root")
            return
        }
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(tmp).use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            Log.w(tag, "atomic rename failed for ${target.path}")
            tmp.delete()
            return
        }
        legacyByTarget.remove(target.canonicalPath)
            ?.takeIf(::isContained)
            ?.delete()
    }

    internal fun containedReadCandidate(file: File): File? {
        if (!isContained(file)) return null
        if (file.isFile) return file
        return legacyByTarget[file.canonicalPath]
            ?.takeIf(::isContained)
            ?.takeIf { it.isFile }
    }

    private fun isContained(file: File): Boolean =
        runCatching {
            file.canonicalPath.startsWith(canonicalRoot.path + File.separator)
        }.getOrDefault(false)

    private fun containedFile(directory: File, fileName: String): File? =
        runCatching {
            File(directory, fileName).canonicalFile.takeIf { target ->
                target.parentFile?.canonicalFile == directory.canonicalFile &&
                    isContained(target)
            }
        }.getOrNull()

    companion object {
        val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    }
}
