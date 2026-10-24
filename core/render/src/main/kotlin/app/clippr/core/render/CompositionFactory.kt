package app.clippr.core.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.OverlaySettings
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.GainProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Crop
import androidx.media3.effect.GaussianBlur
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.SingleColorLut
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import app.clippr.core.model.Animation
import app.clippr.core.model.Clip
import app.clippr.core.model.FitMode
import app.clippr.core.model.StyleEffectType
import app.clippr.core.model.Transform
import app.clippr.core.model.TransformKeyframe
import app.clippr.core.model.render.Affine
import app.clippr.core.model.render.AudioEnvelope
import app.clippr.core.model.render.AudioMix
import app.clippr.core.model.render.AudioSegment
import app.clippr.core.model.render.FrameGeometry
import app.clippr.core.model.render.RenderPlan
import app.clippr.core.model.render.TextOverlayPlan
import app.clippr.core.model.render.VideoSegment
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Translates a [RenderPlan] into a Media3 [Composition]. Both [PreviewEngine] and [ExportEngine]
 * call this with the same plan; they differ only in output size.
 *
 * Per visual clip the effect chain is: Crop → Presentation (fit/fill into the canvas) → blur →
 * colour (all adjustments baked into one 3D LUT) → stylized shader effects (see
 * [ShaderEffects]) → canvas matrix (flip, scale, rotate, position; keyframed if animated) →
 * shake → vignette/grain overlays → opacity × fades. Opacity is applied as an
 * RGB scale, which is exact because the MVP composites a single video layer over black.
 *
 * Effects receive composition-relative frame timestamps in both CompositionPlayer and
 * Transformer (verified in the Media3 1.11.1 sources), so animated effects subtract the clip's
 * timeline start to get clip-local time.
 */
@OptIn(UnstableApi::class)
object CompositionFactory {

    /** Returns null when the plan has nothing to render. */
    fun create(plan: RenderPlan, outputWidth: Int, outputHeight: Int, resources: RenderResources? = null): Composition? {
        if (plan.isEmpty || plan.video.isEmpty()) return null
        val fps = max(1, plan.frameRate.fps.roundToInt())
        val canvasAspect = outputWidth.toFloat() / outputHeight

        val videoSequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO))
        for (segment in plan.video) {
            when (segment) {
                is VideoSegment.Gap -> videoSequence.addGap(segment.durationUs)
                is VideoSegment.Media -> videoSequence.addItem(videoItem(segment, outputWidth, outputHeight, canvasAspect, fps, resources))
            }
        }
        val sequences = mutableListOf(videoSequence.build())

        for (lane in plan.audioLanes) {
            val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
            for (segment in lane) {
                when (segment) {
                    is AudioSegment.Gap -> builder.addGap(segment.durationUs)
                    is AudioSegment.Media -> builder.addItem(audioItem(segment))
                }
            }
            sequences += builder.build()
        }

        val compositionEffects = buildList<Effect> {
            add(Presentation.createForWidthAndHeight(outputWidth, outputHeight, Presentation.LAYOUT_SCALE_TO_FIT))
            if (plan.texts.isNotEmpty()) {
                add(OverlayEffect(plan.texts.map<TextOverlayPlan, TextureOverlay> { TimedTextOverlay(it, outputWidth, outputHeight, resources?.fonts) }))
            }
        }

        return Composition.Builder(sequences)
            .setEffects(Effects(emptyList(), compositionEffects))
            .build()
    }

    private fun videoItem(
        segment: VideoSegment.Media,
        outputWidth: Int,
        outputHeight: Int,
        canvasAspect: Float,
        fps: Int,
        resources: RenderResources?,
    ): EditedMediaItem {
        val uri = Uri.parse(segment.asset.uri)
        val mediaItem = if (segment.isImage) {
            MediaItem.Builder()
                .setUri(uri)
                .setImageDurationMs(max(1L, (segment.durationUs / 1000.0).roundToLong()))
                .build()
        } else {
            clippedItem(uri, segment.sourceStartUs, segment.sourceEndUs)
        }

        val transform = segment.transform
        val fx = segment.effects
        val animated = segment.keyframes.isNotEmpty()
        val videoEffects = buildList<Effect> {
            if (!transform.crop.isNone) {
                val c = FrameGeometry.cropNdc(transform.crop)
                add(Crop(c[0], c[1], c[2], c[3]))
            }
            val layout = when (transform.fit) {
                FitMode.FIT -> Presentation.LAYOUT_SCALE_TO_FIT
                FitMode.FILL -> Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
            }
            add(Presentation.createForWidthAndHeight(outputWidth, outputHeight, layout))
            if (fx.blur > 0f) add(GaussianBlur(max(0.5f, fx.blur * 22f * outputHeight / 1080f)))
            if (!segment.color.isIdentity && resources != null) {
                add(SingleColorLut.createFromCube(resources.bakedLut(segment.color)))
            }
            // Stylized effects work on the framed clip, before it is placed on the canvas.
            fx.styles.filter { it.type != StyleEffectType.SHAKE }.forEach { style ->
                ShaderEffects.create(style, segment.timelineStartUs)?.let(::add)
            }
            if (animated) {
                add(KeyframedMatrix(segment, canvasAspect))
            } else {
                val matrix = FrameGeometry.ndcMatrix(transform, canvasAspect)
                if (!matrix.isIdentity) add(NdcMatrixTransformation(matrix))
            }
            // Shake moves the placed clip like a handheld camera.
            fx.styles.firstOrNull { it.type == StyleEffectType.SHAKE }?.let { style ->
                ShaderEffects.create(style, segment.timelineStartUs)?.let(::add)
            }
            val overlays = buildList<TextureOverlay> {
                if (fx.vignette > 0f) add(VignetteOverlay(outputWidth, outputHeight, fx.vignette))
                if (fx.grain > 0f) add(GrainOverlay(outputWidth, outputHeight, fx.grain))
            }
            if (overlays.isNotEmpty()) add(OverlayEffect(overlays))
            if (animated || fx.hasFades) {
                add(TimedOpacity(segment))
            } else if (transform.opacity < 1f) {
                add(ConstantOpacity(transform.opacity))
            }
        }

        val builder = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(segment.audio == null)
            .setFrameRate(fps)
            .setEffects(Effects(gainProcessors(segment.audio, segment.durationUs), videoEffects))
        if (!segment.isImage) builder.setDurationUs(segment.asset.durationUs)
        if (segment.speed != 1f) builder.setSpeed(ConstantSpeedProvider(segment.speed))
        return builder.build()
    }

    private fun audioItem(segment: AudioSegment.Media): EditedMediaItem {
        val builder = EditedMediaItem.Builder(clippedItem(Uri.parse(segment.asset.uri), segment.sourceStartUs, segment.sourceEndUs))
            .setRemoveVideo(true)
            .setDurationUs(segment.asset.durationUs)
            .setEffects(Effects(gainProcessors(segment.mix, segment.durationUs), emptyList()))
        if (segment.speed != 1f) builder.setSpeed(ConstantSpeedProvider(segment.speed))
        return builder.build()
    }

    private fun clippedItem(uri: Uri, startUs: Long, endUs: Long): MediaItem =
        MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionUs(startUs)
                    .setEndPositionUs(endUs)
                    .build(),
            )
            .build()

    private fun gainProcessors(mix: AudioMix?, durationUs: Long): List<AudioProcessor> =
        if (mix == null || mix.isUnity) emptyList() else listOf(GainProcessor(EnvelopeGainProvider(AudioEnvelope(mix, durationUs))))
}

/** A clip used only to evaluate keyframes with [Animation]. */
internal fun evaluationClip(transform: Transform, keyframes: List<TransformKeyframe>) = Clip(
    id = "eval",
    assetId = null,
    timelineStartUs = 0,
    sourceStartUs = 0,
    sourceEndUs = Long.MAX_VALUE / 4,
    transform = transform,
    keyframes = keyframes,
)

/** Applies a precomputed NDC [Affine] (see [FrameGeometry.ndcMatrix]). */
@OptIn(UnstableApi::class)
internal class NdcMatrixTransformation(affine: Affine) : MatrixTransformation {
    private val matrix = Matrix().apply { setValues(affine.values) }
    override fun getMatrix(presentationTimeUs: Long): Matrix = matrix
}

/** Placement that follows the clip's keyframes over time. */
@OptIn(UnstableApi::class)
internal class KeyframedMatrix(private val segment: VideoSegment.Media, private val canvasAspect: Float) : MatrixTransformation {
    private val clip = evaluationClip(segment.transform, segment.keyframes)
    private val matrix = Matrix()

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val t = Animation.transformAt(clip, segment.sourceTimeAt(presentationTimeUs))
        matrix.setValues(FrameGeometry.ndcMatrix(t, canvasAspect).values)
        return matrix
    }
}

private fun scaleMatrix(o: Float): FloatArray = floatArrayOf(
    o, 0f, 0f, 0f,
    0f, o, 0f, 0f,
    0f, 0f, o, 0f,
    0f, 0f, 0f, 1f,
)

/** Constant opacity over black. */
@OptIn(UnstableApi::class)
internal class ConstantOpacity(opacity: Float) : RgbMatrix {
    private val matrix = scaleMatrix(opacity.coerceIn(0f, 1f))
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = matrix
}

/** Keyframed opacity multiplied by fade-from/to-black. */
@OptIn(UnstableApi::class)
internal class TimedOpacity(private val segment: VideoSegment.Media) : RgbMatrix {
    private val clip = evaluationClip(segment.transform, segment.keyframes)

    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val local = presentationTimeUs - segment.timelineStartUs
        val opacity = Animation.transformAt(clip, segment.sourceTimeAt(presentationTimeUs)).opacity
        val fade = Animation.fadeFactor(segment.effects, local, segment.durationUs)
        return scaleMatrix((opacity * fade).coerceIn(0f, 1f))
    }
}

/** Darkened edges: a radial gradient rendered at quarter resolution and scaled up. */
@OptIn(UnstableApi::class)
internal class VignetteOverlay(width: Int, height: Int, strength: Float) : BitmapOverlay() {
    private val bitmap: Bitmap = run {
        val w = max(8, width / 4)
        val h = max(8, height / 4)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val radius = hypot(w / 2f, h / 2f)
        val edge = (255 * (0.35f + 0.6f * strength)).roundToInt().coerceIn(0, 255)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w / 2f, h / 2f, radius,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(edge, 0, 0, 0)),
                floatArrayOf(0f, 0.55f - 0.25f * strength, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        Canvas(bmp).drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        bmp
    }
    private val settings: OverlaySettings = StaticOverlaySettings.Builder()
        .setScale(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        .build()

    override fun getBitmap(presentationTimeUs: Long): Bitmap = bitmap
    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings
}

/** Film grain: a few noise frames at half resolution, cycled every frame. */
@OptIn(UnstableApi::class)
internal class GrainOverlay(width: Int, height: Int, strength: Float) : BitmapOverlay() {
    private val frames: List<Bitmap> = run {
        val w = max(8, width / 2)
        val h = max(8, height / 2)
        val alpha = (strength * 70f).roundToInt().coerceIn(1, 255)
        val random = Random(42)
        List(3) {
            val pixels = IntArray(w * h) {
                val v = random.nextInt(256)
                Color.argb((alpha * (0.4f + 0.6f * random.nextFloat())).roundToInt(), v, v, v)
            }
            Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        }
    }
    private val settings: OverlaySettings = StaticOverlaySettings.Builder()
        .setScale(width.toFloat() / frames[0].width, height.toFloat() / frames[0].height)
        .build()

    override fun getBitmap(presentationTimeUs: Long): Bitmap = frames[((presentationTimeUs / 41_667L) % frames.size).toInt().coerceAtLeast(0)]
    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings
}

/** A constant playback speed. */
@OptIn(UnstableApi::class)
internal class ConstantSpeedProvider(private val speed: Float) : SpeedProvider {
    override fun getSpeed(timeUs: Long): Float = speed
    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
}

/** Adapts the model's [AudioEnvelope] (time based) to Media3's sample-based gain interface. */
@OptIn(UnstableApi::class)
internal class EnvelopeGainProvider(private val envelope: AudioEnvelope) : GainProcessor.GainProvider {

    override fun getGainFactorAtSamplePosition(samplePosition: Long, sampleRate: Int): Float =
        envelope.gainAt(samplePosition * 1_000_000L / sampleRate)

    override fun isUnityUntil(samplePosition: Long, sampleRate: Int): Long {
        val until = envelope.unityUntilUs(samplePosition * 1_000_000L / sampleRate)
        return when (until) {
            AudioEnvelope.NOT_UNITY -> C.TIME_UNSET
            AudioEnvelope.UNITY_TO_END -> C.TIME_END_OF_SOURCE
            else -> until * sampleRate / 1_000_000L
        }
    }
}

/**
 * A text clip drawn over the composited frame, visible during `[startUs, endUs)` of the
 * composition. Static text uses fixed settings; animated text (keyframes or fades) computes its
 * position, rotation, scale and alpha for every frame. The bitmap is rendered once at output
 * resolution, at the largest scale it reaches, so it is only ever scaled down.
 */
@OptIn(UnstableApi::class)
internal class TimedTextOverlay(
    private val plan: TextOverlayPlan,
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val fonts: FontLibrary?,
) : BitmapOverlay() {

    private val evalClip = evaluationClip(plan.transform, plan.keyframes)
    private val baseScale: Float = max(0.05f, (plan.keyframes.map { it.scale } + plan.transform.scale).max())

    private val bitmap: Bitmap by lazy {
        TextRenderer.render(plan.style, outputWidth, outputHeight, baseScale, fonts)
    }

    private val hidden: OverlaySettings = StaticOverlaySettings.Builder().setAlphaScale(0f).build()
    private val static: OverlaySettings = settingsFor(plan.transform, 1f)

    private fun settingsFor(t: Transform, alphaMultiplier: Float): OverlaySettings {
        val s = (t.scale / baseScale).coerceAtLeast(0.01f)
        return StaticOverlaySettings.Builder()
            .setAlphaScale((t.opacity * alphaMultiplier).coerceIn(0f, 1f))
            .setBackgroundFrameAnchor(t.positionX.coerceIn(-1f, 1f), t.positionY.coerceIn(-1f, 1f))
            .setScale(s, s)
            // Media3 rotates counter-clockwise; Clippr's rotation is clockwise.
            .setRotationDegrees(-t.rotationDegrees)
            .build()
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap = bitmap

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        if (presentationTimeUs < plan.startUs || presentationTimeUs >= plan.endUs) return hidden
        if (!plan.isAnimated) return static
        val local = presentationTimeUs - plan.startUs
        val t = Animation.transformAt(evalClip, plan.sourceStartUs + local)
        return settingsFor(t, Animation.fadeFactor(plan.effects, local, plan.endUs - plan.startUs))
    }
}
