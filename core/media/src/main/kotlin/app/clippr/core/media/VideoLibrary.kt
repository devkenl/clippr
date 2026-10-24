package app.clippr.core.media

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Publishes exported videos to the shared Movies/Clippr folder via MediaStore. On Android 10+
 * an app may add files to its own MediaStore entries without any storage permission.
 */
class VideoLibrary(private val context: Context) {

    /** Copies [file] into Movies/Clippr and returns the new content URI. */
    suspend fun publish(file: File, displayName: String): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Clippr")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("Could not create a gallery entry")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("Could not write to the gallery")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /** Whether the exported video still exists (the user may have deleted it in the gallery). */
    suspend fun exists(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (e: Exception) {
            false
        }
    }

    fun shareIntent(uri: Uri, title: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, title)
    }

    fun viewIntent(uri: Uri): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "video/mp4")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
