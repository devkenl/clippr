package app.clippr.core.render

import androidx.annotation.OptIn
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.StaticOverlaySettings
import app.clippr.core.model.LayerLook
import app.clippr.core.model.render.RenderPlan

/**
 * Tells Media3's compositor how to stack Clippr's video layers at every frame.
 *
 * Input `i` is the plan's layer `i` (layer 0 is the top one, which Media3 also draws on top).
 * Each layer's opacity, slide and zoom come from [app.clippr.core.model.render.VideoSegment.Media.lookAt]:
 * the clip's keyframed opacity and fades combined with any transition. Layers in a gap are fully
 * transparent, so a gap in an upper layer never covers what is beneath it.
 *
 * The output size is always the canvas size, whatever the inputs are.
 */
@OptIn(UnstableApi::class)
internal class LayerCompositor(
    private val plan: RenderPlan,
    private val width: Int,
    private val height: Int,
) : VideoCompositorSettings {

    private val hidden: OverlaySettings = StaticOverlaySettings.Builder().setAlphaScale(0f).build()
    private val opaque: OverlaySettings = StaticOverlaySettings.Builder().build()

    override fun getOutputSize(inputSizes: List<Size>): Size = Size(width, height)

    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
        val layer = plan.layers.getOrNull(inputId) ?: return hidden
        val media = layer.mediaAt(presentationTimeUs) ?: return hidden
        return settingsFor(media.lookAt(presentationTimeUs))
    }

    private fun settingsFor(look: LayerLook): OverlaySettings {
        if (look == LayerLook.NONE) return opaque
        if (look.alpha <= 0f) return hidden
        val s = look.scale.coerceIn(0.05f, 10f)
        // Media3 positions an overlay by pinning a point of it (overlay anchor) to a point of the
        // background (background anchor), both limited to [-1, 1]. Splitting the move between the
        // two reaches offsets of up to two frame widths, enough to slide a layer fully off-screen.
        val dx = look.offsetX.coerceIn(-2f * s, 2f * s)
        val dy = look.offsetY.coerceIn(-2f * s, 2f * s)
        return StaticOverlaySettings.Builder()
            .setAlphaScale(look.alpha.coerceIn(0f, 1f))
            .setScale(s, s)
            .setBackgroundFrameAnchor((dx / 2f).coerceIn(-1f, 1f), (dy / 2f).coerceIn(-1f, 1f))
            .setOverlayFrameAnchor((-dx / (2f * s)).coerceIn(-1f, 1f), (-dy / (2f * s)).coerceIn(-1f, 1f))
            .build()
    }
}
