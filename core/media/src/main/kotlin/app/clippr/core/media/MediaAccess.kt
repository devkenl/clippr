package app.clippr.core.media

import android.content.ContentResolver
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

    /**
     * True when [uri] is served by a cloud or streaming app rather than stored on this device.
     * Such files are streamed on demand and often can't seek reliably, so Clippr copies them.
     *
     * Known on-device providers are trusted; any other provider counts as cloud, as does a file
     * whose descriptor is a pipe (no size), which is how streaming providers usually answer.
     */
    fun isCloudDocument(uri: Uri): Boolean {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return false
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return true
        if (uri.authority !in LOCAL_AUTHORITIES) return true
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize < 0 } ?: true
        } catch (e: Exception) {
            false
        }
    }

    /** Ids of [assets] that still point at cloud apps (projects from before 0.2.0). */
    suspend fun findCloudAssets(assets: List<MediaAsset>): Set<String> = withContext(Dispatchers.IO) {
        assets.filter { isCloudDocument(Uri.parse(it.uri)) }.map { it.id }.toSet()
    }

    companion object {
        /** Document providers that serve files stored on the device itself. */
        val LOCAL_AUTHORITIES = setOf(
            "com.android.externalstorage.documents",
            "com.android.providers.media.documents",
            "com.android.providers.downloads.documents",
            "media",
            "com.android.providers.media.photopicker",
        )
    }
}
