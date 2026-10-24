package app.clippr.core.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.clippr.core.model.MediaAsset
import app.clippr.core.model.MediaType
import app.clippr.core.model.Project
import app.clippr.core.model.ProjectSettings
import app.clippr.core.model.edit.AddAssetClip
import app.clippr.core.model.edit.DeleteClips
import app.clippr.core.model.edit.ImportAssets
import app.clippr.core.model.edit.SplitClip
import app.clippr.core.model.edit.TrimClipStart
import app.clippr.core.model.time.FrameRate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Renders a real project on the device: generates a 6-second source clip, trims and splits it on
 * the timeline, removes a section, exports, and checks the output's duration matches the edit.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class ExportEndToEndTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun exportReflectsTimelineEdits() = runBlocking {
        val source = generateSourceVideo(durationMs = 6_000)
        val asset = MediaAsset(
            id = "src",
            uri = Uri.fromFile(source).toString(),
            displayName = source.name,
            type = MediaType.VIDEO,
            durationUs = probeDurationUs(source),
            width = 640,
            height = 360,
            hasAudio = false,
        )
        var p = Project("p", "e2e", ProjectSettings(640, 360, FrameRate.FPS_30))
        p = ImportAssets(listOf(asset)).apply(p)
        p = AddAssetClip("src", "a").apply(p) // [0, 6s)
        p = TrimClipStart("a", 1_000_000).apply(p) // [1s, 6s)
        p = SplitClip("a", 3_000_000, "b").apply(p) // a = [1s,3s), b = [3s,6s)
        p = DeleteClips(setOf("b")).apply(p) // timeline: gap 1s + a 2s = 3s
        val expectedUs = p.durationUs
        assertTrue(abs(expectedUs - 3_000_000) < 50_000, "unexpected plan duration $expectedUs")

        val out = File(context.cacheDir, "e2e-export.mp4")
        val engine = withContext(Dispatchers.Main) { ExportEngine(context) }
        withContext(Dispatchers.Main) { engine.start(p, ExportSettings(360, FrameRate.FPS_30), out) }
        val final = withTimeout(120_000) {
            engine.state.first { it is ExportState.Completed || it is ExportState.Failed }
        }
        assertIs<ExportState.Completed>(final, (final as? ExportState.Failed)?.message)
        val actualUs = probeDurationUs(out)
        assertTrue(abs(actualUs - expectedUs) < 150_000, "exported $actualUs us, expected about $expectedUs us")
    }

    private fun probeDurationUs(file: File): Long {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            return (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L) * 1000L
        } finally {
            r.release()
        }
    }

    /** Uses Media3 to encode a still image into a short H.264 clip to use as test input. */
    private suspend fun generateSourceVideo(durationMs: Long): File {
        val png = File(context.cacheDir, "e2e-source.png")
        val bmp = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.rgb(167, 139, 250))
        FileOutputStream(png).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val out = File(context.cacheDir, "e2e-source.mp4").apply { delete() }
        val item = EditedMediaItem.Builder(
            MediaItem.Builder().setUri(Uri.fromFile(png)).setImageDurationMs(durationMs).build(),
        ).setFrameRate(30).build()
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val transformer = Transformer.Builder(context)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) = cont.resume(Unit)
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) =
                            cont.resumeWithException(exportException)
                    })
                    .build()
                transformer.start(Composition.Builder(EditedMediaItemSequence.withVideoFrom(listOf(item))).build(), out.absolutePath)
                cont.invokeOnCancellation { transformer.cancel() }
            }
        }
        return out
    }
}
