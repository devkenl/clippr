package app.clippr.core.model

/** Groups shown as category chips in the effects browser. */
enum class EffectCategory(val label: String) {
    ENHANCE("Enhance"),
    LIGHT("Light"),
    BLUR("Blur"),
    DISTORTION("Distortion"),
    STYLIZE("Stylize"),
    RETRO("Retro"),
    MOTION("Motion"),
}

/**
 * Stylized video effects rendered by GPU shaders (see `core:render` ShaderEffects). Each one has
 * a single [StyleEffect.amount] in `0..1`. [animated] effects change over time (noise, flicker,
 * shake) and are evaluated from the frame timestamp, so preview and export match.
 *
 * [id] is the stable name stored in project files; never rename it.
 */
enum class StyleEffectType(
    val id: String,
    val label: String,
    val category: EffectCategory,
    val animated: Boolean = false,
    val defaultAmount: Float = 0.6f,
) {
    SHARPEN("sharpen", "Sharpen", EffectCategory.ENHANCE, defaultAmount = 0.5f),
    GLOW("glow", "Glow", EffectCategory.LIGHT),
    LIGHT_LEAK("light_leak", "Light leak", EffectCategory.LIGHT, animated = true),
    ZOOM_BLUR("zoom_blur", "Zoom blur", EffectCategory.BLUR, defaultAmount = 0.4f),
    CHROMATIC("chromatic", "Chromatic", EffectCategory.DISTORTION, defaultAmount = 0.4f),
    FISHEYE("fisheye", "Fisheye", EffectCategory.DISTORTION, defaultAmount = 0.5f),
    MIRROR("mirror", "Mirror", EffectCategory.DISTORTION, defaultAmount = 1f),
    PIXELATE("pixelate", "Pixelate", EffectCategory.STYLIZE, defaultAmount = 0.4f),
    POSTERIZE("posterize", "Posterize", EffectCategory.STYLIZE, defaultAmount = 0.5f),
    SKETCH("sketch", "Sketch", EffectCategory.STYLIZE, defaultAmount = 0.8f),
    VHS("vhs", "VHS", EffectCategory.RETRO, animated = true),
    OLD_FILM("old_film", "Old film", EffectCategory.RETRO, animated = true),
    GLITCH("glitch", "Glitch", EffectCategory.RETRO, animated = true, defaultAmount = 0.5f),
    SHAKE("shake", "Shake", EffectCategory.MOTION, animated = true, defaultAmount = 0.4f),
    ;

    companion object {
        fun fromId(id: String?): StyleEffectType? = entries.firstOrNull { it.id == id }
    }
}

/** One applied stylized effect. Effects stack in list order. */
data class StyleEffect(val type: StyleEffectType, val amount: Float = type.defaultAmount) {
    fun sanitized(): StyleEffect = copy(amount = if (amount.isFinite()) amount.coerceIn(0f, 1f) else type.defaultAmount)
}

/** Editing helpers for a clip's stylized effects. */
object StyleEffects {

    /** Adds [type], or removes it when the clip already has it. */
    fun toggle(effects: VisualEffects, type: StyleEffectType): VisualEffects =
        if (effects.styles.any { it.type == type }) {
            effects.copy(styles = effects.styles.filterNot { it.type == type })
        } else {
            effects.copy(styles = effects.styles + StyleEffect(type))
        }

    fun setAmount(effects: VisualEffects, type: StyleEffectType, amount: Float): VisualEffects =
        effects.copy(styles = effects.styles.map { if (it.type == type) it.copy(amount = amount.coerceIn(0f, 1f)) else it })

    fun remove(effects: VisualEffects, type: StyleEffectType): VisualEffects =
        effects.copy(styles = effects.styles.filterNot { it.type == type })
}
