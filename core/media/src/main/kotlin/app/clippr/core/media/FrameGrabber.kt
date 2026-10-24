package app.clippr.core.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import app.clippr.core.model.MediaAsset
import app.clippr.core.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** Extracts a still frame from a video into app storage, for freeze frames. */
class FrameGrabber(private val context: Context) {

    /**
     * Saves the frame of [asset] closest to [sourceUs] as a PNG and returns it as a new image
     * asset, or null if the frame cannot be decoded.
     */
    suspend fun grab(asset: MediaAsset, sourceUs: Long, maxLongSide: Int = 1920): MediaAsset? = withContext(Dispatchers.IO) {
        if (asset.type != MediaType.VIDEO) return@withContext null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(asset.uri))
            val t = sourceUs.coerceIn(0, (asset.durationUs - 1).coerceAtLeast(0))
            val frame = retriever.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST) ?: return@withContext null
            val long = max(frame.width, frame.height)
            val scaled = if (long > maxLongSide) {
                val k = maxLongSide.toFloat() / long
                Bitmap.createScaledBitmap(frame, (frame.width * k).roundToInt(), (frame.height * k).roundToInt(), true)
            } else {
                frame
            }
            val dir = File(context.filesDir, "frames").apply { mkdirs() }
            val id = UUID.randomUUID().toString()
            val file = File(dir, "$id.png")
            FileOutputStream(file).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
            MediaAsset(
                id = "frame-$id",
                uri = Uri.fromFile(file).toString(),
                displayName = "Freeze – ${asset.displayName}",
                type = MediaType.IMAGE,
                durationUs = 0,
                width = scaled.width,
                height = scaled.height,
                mimeType = "image/png",
                sizeBytes = file.length(),
            )
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
