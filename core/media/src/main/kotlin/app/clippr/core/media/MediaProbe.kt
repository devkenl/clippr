package app.clippr.core.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import app.clippr.core.model.MediaAsset
import app.clippr.core.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** Outcome of inspecting a file the user picked. */
sealed interface ProbeResult {
    data class Success(val asset: MediaAsset) : ProbeResult
    data class Failure(val uri: Uri, val displayName: String, val reason: String) : ProbeResult
}

/**
 * Reads duration, dimensions, rotation and track information from picked media using platform
 * APIs only. Files that cannot be read are reported as [ProbeResult.Failure] rather than
 * imported with made-up metadata.
 */
class MediaProbe(private val context: Context) {

    private val resolver: ContentResolver get() = context.contentResolver

    suspend fun probe(uri: Uri, assetId: String = UUID.randomUUID().toString()): ProbeResult =
        withContext(Dispatchers.IO) {
            val (name, size) = queryNameAndSize(uri)
            val mime = resolver.getType(uri) ?: guessMime(name)
            try {
                val asset = when {
                    mime == null -> return@withContext ProbeResult.Failure(uri, name, "Unknown file type")
                    mime.startsWith("image/") -> probeImage(uri, assetId, name, mime, size)
                    mime.startsWith("video/") || mime.startsWith("audio/") -> probeTimed(uri, assetId, name, mime, size)
                    else -> return@withContext ProbeResult.Failure(uri, name, "Unsupported file type ($mime)")
                }
                if (asset == null) ProbeResult.Failure(uri, name, "This file could not be read") else ProbeResult.Success(asset)
            } catch (e: SecurityException) {
                ProbeResult.Failure(uri, name, "Permission to read this file was denied")
            } catch (e: Exception) {
                ProbeResult.Failure(uri, name, e.message ?: "This file could not be read")
            }
        }

    private fun probeImage(uri: Uri, id: String, name: String, mime: String, size: Long): MediaAsset? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        val rotation = try {
            resolver.openInputStream(uri)?.use { exifRotation(ExifInterface(it)) } ?: 0
        } catch (e: Exception) {
            0
        }
        return MediaAsset(
            id = id,
            uri = uri.toString(),
            displayName = name,
            type = MediaType.IMAGE,
            durationUs = 0,
            width = opts.outWidth,
            height = opts.outHeight,
            rotationDegrees = rotation,
            mimeType = mime,
            sizeBytes = size,
        )
    }

    private fun probeTimed(uri: Uri, id: String, name: String, mime: String, size: Long): MediaAsset? {
        var durationUs = 0L
        var hasVideo = false
        var hasAudio = false
        var frameRate: Float? = null
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val trackMime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    durationUs = maxOf(durationUs, format.getLong(MediaFormat.KEY_DURATION))
                }
                when {
                    trackMime.startsWith("video/") -> {
                        hasVideo = true
                        if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                            frameRate = try {
                                format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                            } catch (e: ClassCastException) {
                                format.getFloat(MediaFormat.KEY_FRAME_RATE)
                            }
                        }
                    }
                    trackMime.startsWith("audio/") -> hasAudio = true
                }
            }
        } finally {
            extractor.release()
        }

        var width = 0
        var height = 0
        var rotation = 0
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            if (durationUs <= 0) {
                durationUs = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000L
            }
            if (hasVideo) {
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            }
        } finally {
            retriever.release()
        }

        if (durationUs <= 0 || (!hasVideo && !hasAudio)) return null
        val type = if (hasVideo) MediaType.VIDEO else MediaType.AUDIO
        return MediaAsset(
            id = id,
            uri = uri.toString(),
            displayName = name,
            type = type,
            durationUs = durationUs,
            width = width,
            height = height,
            rotationDegrees = rotation,
            frameRate = frameRate,
            hasVideo = hasVideo,
            hasAudio = hasAudio,
            mimeType = mime,
            sizeBytes = size,
        )
    }

    private fun queryNameAndSize(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "Untitled"
        var size = 0L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        } catch (e: Exception) {
            // Fall back to the URI's last path segment.
        }
        return name to size
    }

    private fun guessMime(name: String): String? = guessMimeFromName(name)

    companion object {
        fun exifRotation(exif: ExifInterface): Int =
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
                ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
                ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
                else -> 0
            }
    }
}

private fun guessMimeFromName(name: String): String? {
    val ext = name.substringAfterLast('.', "").lowercase()
    return if (ext.isEmpty()) null else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
}
