package app.clippr.core.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.clippr.core.model.MediaAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Keeps and checks access to user-picked media through the Storage Access Framework. */
class MediaAccess(private val context: Context) {

    /**
     * Keeps read access to [uri] across restarts. Must be called for URIs returned by
     * ACTION_OPEN_DOCUMENT, or projects lose their media after the app restarts.
     */
    fun persistReadPermission(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Some providers do not offer persistable grants; access then lasts for this session.
        }
    }

    /** Returns the ids of [assets] whose files can no longer be opened. */
    suspend fun findMissing(assets: List<MediaAsset>): Set<String> = withContext(Dispatchers.IO) {
        assets.filterNot { isReadable(Uri.parse(it.uri)) }.map { it.id }.toSet()
    }

    fun isReadable(uri: Uri): Boolean = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (e: Exception) {
        false
    }
}
