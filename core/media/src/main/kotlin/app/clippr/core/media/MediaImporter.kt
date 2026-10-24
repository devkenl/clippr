package app.clippr.core.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import app.clippr.core.model.MediaAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Progress of one file during import. */
data class ImportProgress(val displayName: String, val index: Int, val total: Int, val copiedFraction: Float?)

sealed interface ImportOutcomeMedia {
    /** [copied] is true when Clippr made a private copy (the file came from a cloud app). */
    data class Imported(val asset: MediaAsset, val copied: Boolean) : ImportOutcomeMedia
    data class Failed(val displayName: String, val reason: String) : ImportOutcomeMedia
}

/**
 * Brings picked files into a project.
 *
 * Files on the device (the Files app, Downloads, the gallery) are referenced in place through a
 * persisted read permission. Files offered by cloud apps such as Google Drive are different: the
 * app streams them on demand and the stream can stop working or refuse to seek, which breaks
 * playback and export. Those files are copied once into Clippr's private storage
 * ([MediaStorage.importsDir]) and the project uses the copy.
 */
class MediaImporter(
    private val context: Context,
    private val probe: MediaProbe,
    private val access: MediaAccess,
    /** Whether files from cloud apps are copied (Settings › Import). */
    private val copyCloudFiles: () -> Boolean = { true },
) {
    private val resolver: ContentResolver get() = context.contentResolver

    suspend fun import(uris: List<Uri>, onProgress: (ImportProgress) -> Unit = {}): List<ImportOutcomeMedia> {
        val results = ArrayList<ImportOutcomeMedia>()
        uris.forEachIndexed { i, uri ->
            results += importOne(uri, i, uris.size, onProgress)
        }
        return results
    }

    private suspend fun importOne(uri: Uri, index: Int, total: Int, onProgress: (ImportProgress) -> Unit): ImportOutcomeMedia {
        val name = displayName(uri)
        onProgress(ImportProgress(name, index, total, null))
        access.persistReadPermission(uri)
        if (copyCloudFiles() && access.isCloudDocument(uri)) {
            val copy = try {
                copyToPrivateStorage(uri, name) { f -> onProgress(ImportProgress(name, index, total, f)) }
            } catch (e: IOException) {
                return ImportOutcomeMedia.Failed(name, "Couldn't copy it from the cloud app (${e.message ?: "read error"}). Download it to your device first.")
            } catch (e: SecurityException) {
                return ImportOutcomeMedia.Failed(name, "Permission to read this file was denied")
            }
            return when (val r = probe.probe(Uri.fromFile(copy))) {
                is ProbeResult.Success -> ImportOutcomeMedia.Imported(r.asset.copy(displayName = name, sizeBytes = copy.length()), copied = true)
                is ProbeResult.Failure -> {
                    copy.delete()
                    ImportOutcomeMedia.Failed(name, r.reason)
                }
            }
        }
        return when (val r = probe.probe(uri)) {
            is ProbeResult.Success -> ImportOutcomeMedia.Imported(r.asset, copied = false)
            is ProbeResult.Failure -> ImportOutcomeMedia.Failed(r.displayName, r.reason)
        }
    }

    /**
     * Makes a private copy of an asset that currently points at a cloud app, for projects made
     * before 0.2.0. Returns the new file URI, or null if the source can no longer be read.
     */
    suspend fun localize(asset: MediaAsset, onProgress: (Float?) -> Unit = {}): Uri? = try {
        Uri.fromFile(copyToPrivateStorage(Uri.parse(asset.uri), asset.displayName, onProgress))
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

    private suspend fun copyToPrivateStorage(uri: Uri, name: String, onProgress: (Float?) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = MediaStorage.importsDir(context)
            val ext = extensionFor(uri, name)
            val target = File(dir, "${UUID.randomUUID()}$ext")
            val partial = File(dir, target.name + ".part")
            val total = sizeOf(uri)
            try {
                val input = resolver.openInputStream(uri) ?: throw IOException("The cloud app returned no data")
                input.use { inp ->
                    partial.outputStream().use { out ->
                        val buffer = ByteArray(256 * 1024)
                        var copied = 0L
                        var lastReport = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = inp.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            copied += n
                            if (copied - lastReport > 4L * 1024 * 1024) {
                                lastReport = copied
                                onProgress(if (total > 0) (copied.toFloat() / total).coerceIn(0f, 1f) else null)
                            }
                        }
                    }
                }
                if (partial.length() == 0L) throw IOException("The file is empty")
                if (!partial.renameTo(target)) throw IOException("Couldn't finish the copy")
                target
            } catch (e: Exception) {
                partial.delete()
                throw e
            }
        }

    private fun displayName(uri: Uri): String {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return uri.lastPathSegment ?: "Untitled"
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            } ?: uri.lastPathSegment ?: "Untitled"
        } catch (e: Exception) {
            uri.lastPathSegment ?: "Untitled"
        }
    }

    private fun sizeOf(uri: Uri): Long = try {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        } ?: 0L
    } catch (e: Exception) {
        0L
    }

    private fun extensionFor(uri: Uri, name: String): String {
        val fromName = name.substringAfterLast('.', "").lowercase().takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) }
        val fromMime = resolver.getType(uri)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        return (fromName ?: fromMime)?.let { ".$it" } ?: ""
    }
}

/** Locations of Clippr's own media files and caches, shared by the importer and Settings › Storage. */
object MediaStorage {
    fun importsDir(context: Context): File = File(context.filesDir, "media").apply { mkdirs() }
    fun voiceoverDir(context: Context): File = File(context.filesDir, "voiceovers").apply { mkdirs() }
    fun thumbnailCacheDir(context: Context): File = File(context.cacheDir, "thumbnails")
    /** Decoded waveform peaks; safe to delete, they are recomputed when needed. */
    fun waveformCacheDir(context: Context): File = File(context.filesDir, "waveforms")

    /** Freeze-frame stills. These are project media, not cache. */
    fun framesDir(context: Context): File = File(context.filesDir, "frames")

    /** True if [uri] points into Clippr's private storage (an imported copy or a voiceover). */
    fun isPrivate(context: Context, uri: String): Boolean {
        if (!uri.startsWith("file:")) return false
        val path = Uri.parse(uri).path ?: return false
        return path.startsWith(context.filesDir.absolutePath)
    }

    fun sizeOf(file: File): Long =
        if (!file.exists()) 0L else if (file.isFile) file.length() else file.listFiles()?.sumOf { sizeOf(it) } ?: 0L
}
