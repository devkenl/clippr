package app.clippr.core.model

import app.clippr.core.model.edit.AddTextClip
import app.clippr.core.model.edit.AddTrack
import app.clippr.core.model.edit.EditCommand
import app.clippr.core.model.edit.UpdateClip
import app.clippr.core.model.time.FrameRate
import app.clippr.core.model.time.US_PER_SECOND

enum class TemplateCategory(val label: String) {
    FORMATS("Formats"),
    STARTERS("Starter projects"),
}

/**
 * A ready-made starting point: a canvas format and, for starters, a few styled title clips that
 * show where your footage and words go. Templates contain no media and no third-party artwork;
 * every title is plain editable text in a bundled open-licence font.
 */
data class ProjectTemplate(
    val id: String,
    val name: String,
    val description: String,
    val category: TemplateCategory,
    val aspect: AspectRatioPreset,
    val shortSide: Int = 1080,
    val frameRate: FrameRate = FrameRate.FPS_30,
    /** Two accent colours for the template's card (ARGB). */
    val colors: Pair<Int, Int> = 0xFF2B2745.toInt() to 0xFFA78BFA.toInt(),
    /** Sample headline shown on the card, rendered in [cardFontId]. */
    val cardTitle: String = name,
    val cardFontId: String = FontIds.DEFAULT,
    val content: List<TemplateText> = emptyList(),
) {
    val settings: ProjectSettings get() = ProjectSettings.create(aspect, shortSide, frameRate)

    /** Edits that add this template's titles to an empty project. */
    fun commands(idFactory: (String) -> String): List<EditCommand> {
        val commands = ArrayList<EditCommand>()
        val laneTracks = HashMap<Int, String>()
        for (text in content) {
            val trackId = if (text.lane == 0) {
                TimelineSequence.MAIN_TEXT_TRACK_ID
            } else {
                laneTracks.getOrPut(text.lane) {
                    idFactory("track").also { commands += AddTrack(TrackKind.TEXT, it) }
                }
            }
            val clipId = idFactory("text")
            commands += AddTextClip(clipId, text.style, trackId, text.startUs, text.durationUs)
            if (text.transform != Transform.DEFAULT || text.keyframes.isNotEmpty() || text.effects != VisualEffects.NONE) {
                commands += UpdateClip(clipId, "Template") { it.copy(transform = text.transform, keyframes = text.keyframes, effects = text.effects) }
            }
        }
        return commands
    }

    /** A new project's content with this template applied. */
    fun applyTo(project: Project, idFactory: (String) -> String): Project =
        commands(idFactory).fold(project.copy(settings = settings)) { p, c -> c.apply(p) }
}

/** One title in a template. [lane] 0 is the main text track; higher lanes get their own track. */
data class TemplateText(
    val style: TextStyle,
    val startUs: Long,
    val durationUs: Long,
    val lane: Int = 0,
    val transform: Transform = Transform.DEFAULT,
    val keyframes: List<TransformKeyframe> = emptyList(),
    val effects: VisualEffects = VisualEffects.NONE,
)

object ProjectTemplates {

    private const val S = US_PER_SECOND
    private const val WHITE = 0xFFF5F5F7.toInt()
    private const val INK = 0xFF101116.toInt()
    private const val VIOLET = 0xFFA78BFA.toInt()
    private const val AMBER = 0xFFFBBF24.toInt()
    private const val MINT = 0xFF5EEAD4.toInt()
    private const val SHADE = 0xB3101116.toInt()

    private fun fades(inUs: Long, outUs: Long) = VisualEffects(fadeInUs = inUs, fadeOutUs = outUs)

    /** A gentle "pop": the title grows from 70% to full size over its first 0.4 s. */
    private fun pop(scale: Float = 1f, x: Float = 0f, y: Float = 0f) = listOf(
        TransformKeyframe(0, x, y, scale * 0.7f, 0f, 0f, Easing.EASE_OUT),
        TransformKeyframe(400_000, x, y, scale, 0f, 1f, Easing.EASE_IN_OUT),
    )

    val all: List<ProjectTemplate> = listOf(
        // Formats: canvas presets only.
        ProjectTemplate("youtube", "YouTube video", "16:9 · 1080p · 30 fps", TemplateCategory.FORMATS, AspectRatioPreset.LANDSCAPE_16_9,
            colors = 0xFF3A1E26.toInt() to 0xFFF87171.toInt(), cardTitle = "16:9"),
        ProjectTemplate("youtube_4k", "YouTube 4K", "16:9 · 2160p · 30 fps", TemplateCategory.FORMATS, AspectRatioPreset.LANDSCAPE_16_9, shortSide = 2160,
            colors = 0xFF3A1E26.toInt() to 0xFFFCA5A5.toInt(), cardTitle = "4K"),
        ProjectTemplate("vertical", "Reels / TikTok / Shorts", "9:16 · 1080p · 30 fps", TemplateCategory.FORMATS, AspectRatioPreset.PORTRAIT_9_16,
            colors = 0xFF16323A.toInt() to MINT, cardTitle = "9:16"),
        ProjectTemplate("insta_post", "Instagram post", "4:5 · 1080p · 30 fps", TemplateCategory.FORMATS, AspectRatioPreset.PORTRAIT_4_5,
            colors = 0xFF3A2A16.toInt() to AMBER, cardTitle = "4:5"),
        ProjectTemplate("square", "Square", "1:1 · 1080p · 30 fps", TemplateCategory.FORMATS, AspectRatioPreset.SQUARE_1_1,
            colors = 0xFF232630.toInt() to WHITE, cardTitle = "1:1"),
        ProjectTemplate("cinematic", "Cinematic", "21:9 · 1080p · 24 fps", TemplateCategory.FORMATS, AspectRatioPreset.CINEMA_21_9, frameRate = FrameRate.FPS_24,
            colors = 0xFF101116.toInt() to VIOLET, cardTitle = "21:9"),

        // Starter projects: format plus editable titles.
        ProjectTemplate(
            "vlog_intro", "Vlog intro", "Big title and a subtitle that fade in", TemplateCategory.STARTERS, AspectRatioPreset.LANDSCAPE_16_9,
            colors = 0xFF2B2745.toInt() to VIOLET, cardTitle = "MY WEEKEND", cardFontId = "bebas_neue",
            content = listOf(
                TemplateText(TextStyle("MY WEEKEND", sizeFraction = 0.16f, fontId = "bebas_neue", shadow = true), 0, 4 * S,
                    transform = Transform(positionY = 0.08f), keyframes = pop(y = 0.08f), effects = fades(0, 500_000)),
                TemplateText(TextStyle("Day one · somewhere new", sizeFraction = 0.045f, fontId = "inter", bold = false, colorArgb = VIOLET), 600_000, 3_400_000,
                    lane = 1, transform = Transform(positionY = -0.16f), effects = fades(400_000, 500_000)),
            ),
        ),
        ProjectTemplate(
            "travel_reel", "Travel reel", "Vertical opener with a place name", TemplateCategory.STARTERS, AspectRatioPreset.PORTRAIT_9_16,
            colors = 0xFF16323A.toInt() to MINT, cardTitle = "Lisbon", cardFontId = "playfair_display",
            content = listOf(
                TemplateText(TextStyle("Lisbon", sizeFraction = 0.075f, fontId = "playfair_display", shadow = true), 0, 3 * S,
                    transform = Transform(positionY = 0.35f), keyframes = pop(y = 0.35f), effects = fades(0, 400_000)),
                TemplateText(TextStyle("3 days · 12 spots", sizeFraction = 0.03f, fontId = "montserrat", colorArgb = MINT), 500_000, 2_500_000,
                    lane = 1, transform = Transform(positionY = 0.27f), effects = fades(300_000, 400_000)),
            ),
        ),
        ProjectTemplate(
            "lower_third", "Lower third", "Name and role for interviews", TemplateCategory.STARTERS, AspectRatioPreset.LANDSCAPE_16_9,
            colors = 0xFF232630.toInt() to AMBER, cardTitle = "Alex Rivera", cardFontId = "montserrat",
            content = listOf(
                TemplateText(TextStyle("Alex Rivera", sizeFraction = 0.055f, fontId = "montserrat", backgroundArgb = SHADE, alignment = TextAlignment.START), S, 5 * S,
                    transform = Transform(positionX = -0.55f, positionY = -0.62f), effects = fades(300_000, 300_000)),
                TemplateText(TextStyle("Founder, Example Studio", sizeFraction = 0.035f, fontId = "inter", bold = false, colorArgb = AMBER, alignment = TextAlignment.START),
                    1_200_000, 4_800_000, lane = 1, transform = Transform(positionX = -0.55f, positionY = -0.76f), effects = fades(300_000, 300_000)),
            ),
        ),
        ProjectTemplate(
            "podcast_clip", "Podcast clip", "Vertical clip with a hook and speaker name", TemplateCategory.STARTERS, AspectRatioPreset.PORTRAIT_9_16,
            colors = 0xFF2A1F3A.toInt() to 0xFFC4B5FD.toInt(), cardTitle = "The hook", cardFontId = "anton",
            content = listOf(
                TemplateText(TextStyle("THE ONE THING\nNOBODY TELLS YOU", sizeFraction = 0.045f, fontId = "anton", strokeWidth = 0.08f), 0, 6 * S,
                    transform = Transform(positionY = 0.55f), effects = fades(200_000, 300_000)),
                TemplateText(TextStyle("@yourshow · Episode 12", sizeFraction = 0.025f, fontId = "inter", bold = false, backgroundArgb = SHADE), 0, 6 * S,
                    lane = 1, transform = Transform(positionY = -0.7f), effects = fades(200_000, 300_000)),
            ),
        ),
        ProjectTemplate(
            "quote_card", "Quote card", "A centred quote with attribution", TemplateCategory.STARTERS, AspectRatioPreset.PORTRAIT_4_5,
            colors = 0xFF3A2A16.toInt() to AMBER, cardTitle = "“Quote”", cardFontId = "caveat",
            content = listOf(
                TemplateText(TextStyle("“Make something\nyou'd want to watch.”", sizeFraction = 0.08f, fontId = "caveat", bold = false), 0, 5 * S,
                    transform = Transform(positionY = 0.06f), effects = fades(600_000, 600_000)),
                TemplateText(TextStyle("— your name", sizeFraction = 0.035f, fontId = "inter", bold = false, colorArgb = AMBER), 800_000, 4_200_000,
                    lane = 1, transform = Transform(positionY = -0.3f), effects = fades(400_000, 600_000)),
            ),
        ),
        ProjectTemplate(
            "promo", "Product promo", "Bold opener and a closing call to action", TemplateCategory.STARTERS, AspectRatioPreset.SQUARE_1_1,
            colors = 0xFF3A1E26.toInt() to 0xFFF87171.toInt(), cardTitle = "NEW", cardFontId = "anton",
            content = listOf(
                TemplateText(TextStyle("NEW ARRIVAL", sizeFraction = 0.12f, fontId = "anton", shadow = true), 0, 3 * S,
                    keyframes = pop(), effects = fades(0, 300_000)),
                TemplateText(TextStyle("Available now", sizeFraction = 0.07f, fontId = "montserrat", colorArgb = INK, backgroundArgb = 0xE6F5F5F7.toInt()), 8 * S, 3 * S,
                    keyframes = pop(), effects = fades(0, 500_000)),
            ),
        ),
        ProjectTemplate(
            "countdown", "Countdown", "3 · 2 · 1 before your video starts", TemplateCategory.STARTERS, AspectRatioPreset.LANDSCAPE_16_9,
            colors = 0xFF101116.toInt() to WHITE, cardTitle = "3 2 1", cardFontId = "oswald",
            content = (3 downTo 1).mapIndexed { i, n ->
                TemplateText(TextStyle("$n", sizeFraction = 0.3f, fontId = "oswald"), i * S, S, keyframes = pop(), effects = fades(0, 250_000))
            },
        ),
        ProjectTemplate(
            "end_screen", "End screen", "Thanks for watching, with a follow-up line", TemplateCategory.STARTERS, AspectRatioPreset.LANDSCAPE_16_9,
            colors = 0xFF2B2745.toInt() to VIOLET, cardTitle = "Thanks!", cardFontId = "pacifico",
            content = listOf(
                TemplateText(TextStyle("Thanks for watching", sizeFraction = 0.1f, fontId = "pacifico", bold = false, shadow = true), 0, 5 * S,
                    transform = Transform(positionY = 0.1f), effects = fades(500_000, 800_000)),
                TemplateText(TextStyle("See you in the next one", sizeFraction = 0.04f, fontId = "inter", bold = false, colorArgb = VIOLET), 800_000, 4_200_000,
                    lane = 1, transform = Transform(positionY = -0.15f), effects = fades(400_000, 800_000)),
            ),
        ),
    )

    fun byId(id: String): ProjectTemplate? = all.firstOrNull { it.id == id }
}
