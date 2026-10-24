package app.clippr.core.model

/**
 * How a clip enters over the clip before it on the same video track.
 *
 * Transitions work like in Vegas Pro: drag a clip so it overlaps the end of the previous one and
 * the overlap becomes the transition. Its length is the overlap; [Transition] only says what it
 * looks like. A clip that doesn't overlap its predecessor keeps its transition setting unused.
 *
 * [id] is the stable name stored in project files; never rename it.
 */
enum class TransitionType(val id: String, val label: String, val group: String) {
    CROSSFADE("crossfade", "Cross dissolve", "Dissolve"),
    DIP_BLACK("dip_black", "Dip to black", "Dissolve"),
    PUSH_LEFT("push_left", "Push left", "Push"),
    PUSH_RIGHT("push_right", "Push right", "Push"),
    PUSH_UP("push_up", "Push up", "Push"),
    PUSH_DOWN("push_down", "Push down", "Push"),
    SLIDE_LEFT("slide_left", "Slide left", "Slide"),
    SLIDE_RIGHT("slide_right", "Slide right", "Slide"),
    SLIDE_UP("slide_up", "Slide up", "Slide"),
    SLIDE_DOWN("slide_down", "Slide down", "Slide"),
    WIPE_LEFT("wipe_left", "Wipe left", "Wipe"),
    WIPE_RIGHT("wipe_right", "Wipe right", "Wipe"),
    WIPE_UP("wipe_up", "Wipe up", "Wipe"),
    WIPE_DOWN("wipe_down", "Wipe down", "Wipe"),
    IRIS("iris", "Circle", "Wipe"),
    ZOOM_IN("zoom_in", "Zoom in", "Zoom"),
    ZOOM_OUT("zoom_out", "Zoom out", "Zoom"),
    ;

    companion object {
        fun fromId(id: String?): TransitionType? = entries.firstOrNull { it.id == id }
    }
}

data class Transition(val type: TransitionType = TransitionType.CROSSFADE, val easing: Easing = Easing.EASE_IN_OUT) {
    companion object {
        val DEFAULT = Transition()
    }
}

/** Shape of a reveal mask used by wipes (and by slides when the incoming clip is underneath). */
enum class MaskShape { LEFT, RIGHT, UP, DOWN, CIRCLE }

/** How one layer looks at a moment: opacity plus a move/zoom in canvas units (NDC, y up). */
data class LayerLook(val alpha: Float = 1f, val offsetX: Float = 0f, val offsetY: Float = 0f, val scale: Float = 1f) {
    companion object {
        val NONE = LayerLook()
    }
}

/**
 * A mask on a clip during a transition. With [invert] false the clip is visible only in the
 * revealed region; with [invert] true only outside it.
 */
data class TransitionMask(val shape: MaskShape, val invert: Boolean)

/**
 * The maths of every transition, shared by preview and export.
 *
 * During a transition two clips play at once on two internal layers. Which one is drawn on top
 * depends on lane assignment ([incomingOnTop]), so each look is defined for both orders and gives
 * the same picture either way. Progress `p` runs from 0 (all outgoing) to 1 (all incoming).
 */
object TransitionMath {

    fun progress(transition: Transition, startUs: Long, endUs: Long, timeUs: Long): Float {
        if (endUs <= startUs) return 1f
        val raw = ((timeUs - startUs).toDouble() / (endUs - startUs)).toFloat().coerceIn(0f, 1f)
        // HOLD would make the cut instantaneous at the end; treat it as linear here.
        val easing = if (transition.easing == Easing.HOLD) Easing.LINEAR else transition.easing
        return Animation.ease(easing, raw)
    }

    /** Look of the incoming clip at progress [p]. */
    fun incoming(type: TransitionType, p: Float, incomingOnTop: Boolean): LayerLook = when (type) {
        TransitionType.CROSSFADE -> LayerLook(alpha = if (incomingOnTop) p else 1f)
        TransitionType.DIP_BLACK -> LayerLook(alpha = ((p - 0.5f) * 2f).coerceIn(0f, 1f))
        TransitionType.PUSH_LEFT, TransitionType.SLIDE_LEFT -> LayerLook(offsetX = 2f * (1f - p))
        TransitionType.PUSH_RIGHT, TransitionType.SLIDE_RIGHT -> LayerLook(offsetX = -2f * (1f - p))
        TransitionType.PUSH_UP, TransitionType.SLIDE_UP -> LayerLook(offsetY = -2f * (1f - p))
        TransitionType.PUSH_DOWN, TransitionType.SLIDE_DOWN -> LayerLook(offsetY = 2f * (1f - p))
        TransitionType.WIPE_LEFT, TransitionType.WIPE_RIGHT, TransitionType.WIPE_UP, TransitionType.WIPE_DOWN, TransitionType.IRIS -> LayerLook.NONE
        TransitionType.ZOOM_IN -> LayerLook(alpha = if (incomingOnTop) p else 1f)
        TransitionType.ZOOM_OUT -> LayerLook(alpha = if (incomingOnTop) p else 1f, scale = 1.4f - 0.4f * p)
    }

    /** Look of the outgoing clip at progress [p]. */
    fun outgoing(type: TransitionType, p: Float, incomingOnTop: Boolean): LayerLook = when (type) {
        TransitionType.CROSSFADE -> LayerLook(alpha = if (incomingOnTop) 1f else 1f - p)
        TransitionType.DIP_BLACK -> LayerLook(alpha = (1f - 2f * p).coerceIn(0f, 1f))
        TransitionType.PUSH_LEFT -> LayerLook(offsetX = -2f * p)
        TransitionType.PUSH_RIGHT -> LayerLook(offsetX = 2f * p)
        TransitionType.PUSH_UP -> LayerLook(offsetY = 2f * p)
        TransitionType.PUSH_DOWN -> LayerLook(offsetY = -2f * p)
        TransitionType.SLIDE_LEFT, TransitionType.SLIDE_RIGHT, TransitionType.SLIDE_UP, TransitionType.SLIDE_DOWN,
        TransitionType.WIPE_LEFT, TransitionType.WIPE_RIGHT, TransitionType.WIPE_UP, TransitionType.WIPE_DOWN, TransitionType.IRIS,
        -> LayerLook.NONE
        TransitionType.ZOOM_IN -> LayerLook(alpha = if (incomingOnTop) 1f else 1f - p, scale = 1f + 0.4f * p)
        TransitionType.ZOOM_OUT -> LayerLook(alpha = if (incomingOnTop) 1f else 1f - p)
    }

    /**
     * The mask a clip needs during a transition, or null. Wipes mask whichever clip is on top;
     * slides need a mask only when the incoming clip is underneath (the outgoing clip is then cut
     * away where the incoming one has arrived).
     */
    fun mask(type: TransitionType, isIncoming: Boolean, incomingOnTop: Boolean): TransitionMask? {
        val onTop = isIncoming == incomingOnTop
        if (!onTop) return null
        val wipeShape = when (type) {
            TransitionType.WIPE_LEFT, TransitionType.SLIDE_LEFT -> MaskShape.LEFT
            TransitionType.WIPE_RIGHT, TransitionType.SLIDE_RIGHT -> MaskShape.RIGHT
            TransitionType.WIPE_UP, TransitionType.SLIDE_UP -> MaskShape.UP
            TransitionType.WIPE_DOWN, TransitionType.SLIDE_DOWN -> MaskShape.DOWN
            TransitionType.IRIS -> MaskShape.CIRCLE
            else -> return null
        }
        val isSlide = type.group == "Slide"
        return when {
            isSlide && isIncoming -> null // a sliding clip on top needs no mask
            isIncoming -> TransitionMask(wipeShape, invert = false)
            else -> TransitionMask(wipeShape, invert = true)
        }
    }

    /**
     * Whether the point (u, v) in texture space (0..1, v up) is in the revealed region at
     * progress [p]. Mirrors the mask shader; used by tests.
     */
    fun revealed(shape: MaskShape, p: Float, u: Float, v: Float, aspect: Float = 16f / 9f): Boolean = when (shape) {
        MaskShape.LEFT -> u >= 1f - p
        MaskShape.RIGHT -> u <= p
        MaskShape.UP -> v <= p
        MaskShape.DOWN -> v >= 1f - p
        MaskShape.CIRCLE -> {
            val dx = (u - 0.5f) * aspect
            val dy = v - 0.5f
            val maxR = kotlin.math.sqrt(aspect * aspect / 4f + 0.25f)
            kotlin.math.sqrt(dx * dx + dy * dy) <= p * maxR
        }
    }
}
