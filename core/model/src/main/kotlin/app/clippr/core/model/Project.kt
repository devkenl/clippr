package app.clippr.core.model

import app.clippr.core.model.time.FrameRate
import kotlin.math.roundToLong

/**
 * Root of the editing model. A [Project] is an immutable value: every edit produces a new
 * instance, which is what makes snapshot-based undo/redo cheap and reliable.
 *
 * Source-media timing (inside a [MediaAsset]) is kept strictly separate from timeline timing
 * (position on a [Track]). [Clip] is the only place where the two are related.
 */
data class Project(
    val id: String,
    val name: String,
    val settings: ProjectSettings,
    val assets: List<MediaAsset> = emptyList(),
    val sequence: TimelineSequence = TimelineSequence.default(),
    val createdAtMs: Long = 0L,
    val modifiedAtMs: Long = 0L,
) {
    fun asset(id: String?): MediaAsset? = if (id == null) null else assets.firstOrNull { it.id == id }

    val durationUs: Long get() = sequence.durationUs
}

/** Output canvas of a project. Width and height are always even (encoder-friendly). */
data class ProjectSettings(
    val width: Int,
    val height: Int,
    val frameRate: FrameRate = FrameRate.FPS_30,
) {
    init {
        require(width > 0 && height > 0) { "Canvas must be non-empty: ${width}x$height" }
    }

    val aspectRatio: Float get() = width.toFloat() / height

    val preset: AspectRatioPreset?
        get() = AspectRatioPreset.entries.firstOrNull {
            it.width.toLong() * height == it.height.toLong() * width
        }

    companion object {
        /** Creates settings for [preset] whose short side is [shortSide] pixels. */
        fun create(
            preset: AspectRatioPreset,
            shortSide: Int = 1080,
            frameRate: FrameRate = FrameRate.FPS_30,
        ): ProjectSettings {
            val (w, h) = preset.dimensionsForShortSide(shortSide)
            return ProjectSettings(w, h, frameRate)
        }
    }
}

enum class AspectRatioPreset(val width: Int, val height: Int, val label: String) {
    LANDSCAPE_16_9(16, 9, "16:9"),
    PORTRAIT_9_16(9, 16, "9:16"),
    SQUARE_1_1(1, 1, "1:1"),
    PORTRAIT_4_5(4, 5, "4:5"),
    CINEMA_21_9(21, 9, "21:9"),
    ;

    /** Even pixel dimensions with the given short side. */
    fun dimensionsForShortSide(shortSide: Int): Pair<Int, Int> {
        val shortRatio = minOf(width, height).toDouble()
        val longSide = evenRound(shortSide * maxOf(width, height) / shortRatio)
        val s = evenRound(shortSide.toDouble())
        return if (width >= height) longSide to s else s to longSide
    }

    private fun evenRound(value: Double): Int = (Math.round(value / 2.0) * 2).toInt().coerceAtLeast(2)
}

enum class MediaType { VIDEO, IMAGE, AUDIO }

/**
 * An imported source file. Clippr never modifies source media. [uri] points to the user's
 * original file (a persisted Storage Access Framework permission on Android), or, for files
 * picked from cloud apps such as Google Drive, to a private copy Clippr made on import.
 */
data class MediaAsset(
    val id: String,
    val uri: String,
    val displayName: String,
    val type: MediaType,
    /** Intrinsic duration of the source. Zero for still images, which have no duration. */
    val durationUs: Long,
    val width: Int = 0,
    val height: Int = 0,
    val rotationDegrees: Int = 0,
    val frameRate: Float? = null,
    val hasVideo: Boolean = type != MediaType.AUDIO,
    val hasAudio: Boolean = type != MediaType.IMAGE,
    val mimeType: String? = null,
    val sizeBytes: Long = 0L,
) {
    /** Images can be stretched to any duration on the timeline. */
    val isUnbounded: Boolean get() = type == MediaType.IMAGE

    /** Width as displayed, after applying container rotation. */
    val displayWidth: Int get() = if (rotationDegrees % 180 != 0) height else width

    /** Height as displayed, after applying container rotation. */
    val displayHeight: Int get() = if (rotationDegrees % 180 != 0) width else height
}

enum class TrackKind { VIDEO, AUDIO, TEXT }

/**
 * The arrangement of tracks. Named `TimelineSequence` rather than `Sequence` to avoid clashing
 * with [kotlin.sequences.Sequence].
 */
data class TimelineSequence(val tracks: List<Track>) {

    /** End of the last clip on any track. */
    val durationUs: Long get() = tracks.maxOfOrNull { it.endUs } ?: 0L

    fun track(id: String): Track? = tracks.firstOrNull { it.id == id }

    fun trackOfClip(clipId: String): Track? = tracks.firstOrNull { t -> t.clips.any { it.id == clipId } }

    fun clip(clipId: String): Clip? {
        for (t in tracks) for (c in t.clips) if (c.id == clipId) return c
        return null
    }

    val allClips: List<Clip> get() = tracks.flatMap { it.clips }

    /** The primary video track, which the MVP renderer composites. */
    val mainVideoTrack: Track? get() = tracks.firstOrNull { it.kind == TrackKind.VIDEO }

    fun replaceTrack(track: Track): TimelineSequence =
        copy(tracks = tracks.map { if (it.id == track.id) track else it })

    companion object {
        const val MAIN_VIDEO_TRACK_ID = "track-video-1"
        const val MAIN_AUDIO_TRACK_ID = "track-audio-1"
        const val MAIN_TEXT_TRACK_ID = "track-text-1"

        /** A new project's layout: one text, one video, and one audio track. */
        fun default(): TimelineSequence = TimelineSequence(
            listOf(
                Track(MAIN_TEXT_TRACK_ID, TrackKind.TEXT, "Text"),
                Track(MAIN_VIDEO_TRACK_ID, TrackKind.VIDEO, "Video 1"),
                Track(MAIN_AUDIO_TRACK_ID, TrackKind.AUDIO, "Audio 1"),
            ),
        )
    }
}

/**
 * A track holds clips that never overlap and are kept sorted by [Clip.timelineStartUs].
 * This invariant is maintained by every edit command.
 */
data class Track(
    val id: String,
    val kind: TrackKind,
    val name: String,
    val clips: List<Clip> = emptyList(),
    val muted: Boolean = false,
    val locked: Boolean = false,
) {
    val endUs: Long get() = clips.maxOfOrNull { it.timelineEndUs } ?: 0L

    fun accepts(type: MediaType?): Boolean = when (kind) {
        TrackKind.VIDEO -> type == MediaType.VIDEO || type == MediaType.IMAGE
        TrackKind.AUDIO -> type == MediaType.AUDIO
        TrackKind.TEXT -> type == null
    }

    fun clipAt(timeUs: Long): Clip? =
        clips.firstOrNull { timeUs >= it.timelineStartUs && timeUs < it.timelineEndUs }

    fun withClips(newClips: List<Clip>): Track = copy(clips = newClips.sortedBy { it.timelineStartUs })
}

/**
 * A clip places the source range `[sourceStartUs, sourceEndUs)` of an asset on the timeline at
 * [timelineStartUs]. At [speed] `s`, the clip occupies `round((sourceEnd - sourceStart) / s)`
 * microseconds of timeline.
 *
 * Text clips have no asset ([assetId] is null) and carry [text]; their source range simply
 * measures their duration from zero.
 */
data class Clip(
    val id: String,
    val assetId: String?,
    val timelineStartUs: Long,
    val sourceStartUs: Long,
    val sourceEndUs: Long,
    val speed: Float = 1f,
    val transform: Transform = Transform.DEFAULT,
    val audio: ClipAudio = ClipAudio.DEFAULT,
    val text: TextStyle? = null,
    /** Animation of placement and opacity, keyed by source time (see [Animation]). */
    val keyframes: List<TransformKeyframe> = emptyList(),
    /** Volume automation, keyed by source time. Overrides [ClipAudio.volume] when present. */
    val volumeKeyframes: List<VolumeKeyframe> = emptyList(),
    val color: ColorGrade = ColorGrade.NONE,
    val effects: VisualEffects = VisualEffects.NONE,
) {
    init {
        require(sourceEndUs > sourceStartUs) { "Clip $id has empty source range" }
        require(speed > 0f) { "Clip $id speed must be positive" }
        require(timelineStartUs >= 0) { "Clip $id starts before zero" }
    }

    val sourceDurationUs: Long get() = sourceEndUs - sourceStartUs

    val durationUs: Long get() = timelineDurationFor(sourceDurationUs, speed)

    val timelineEndUs: Long get() = timelineStartUs + durationUs

    val isText: Boolean get() = text != null

    fun contains(timeUs: Long): Boolean = timeUs >= timelineStartUs && timeUs < timelineEndUs

    /** Maps a timeline time inside this clip to the source time that is shown at that moment. */
    fun sourceTimeAt(timelineUs: Long): Long {
        val offset = (timelineUs - timelineStartUs).coerceIn(0, durationUs)
        return (sourceStartUs + (offset * speed.toDouble()).roundToLong()).coerceAtMost(sourceEndUs)
    }

    companion object {
        fun timelineDurationFor(sourceDurationUs: Long, speed: Float): Long =
            if (speed == 1f) sourceDurationUs else (sourceDurationUs / speed.toDouble()).roundToLong()

        fun sourceDurationFor(timelineDurationUs: Long, speed: Float): Long =
            if (speed == 1f) timelineDurationUs else (timelineDurationUs * speed.toDouble()).roundToLong()
    }
}

enum class FitMode { FIT, FILL }

/** Fractions of the source frame removed from each edge, each in `[0, 1)`. */
data class Crop(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {
    val isNone: Boolean get() = left == 0f && top == 0f && right == 0f && bottom == 0f

    fun sanitized(): Crop {
        fun c(v: Float) = v.finiteOr(0f).coerceIn(0f, MAX_EDGE)
        var l = c(left)
        var r = c(right)
        var t = c(top)
        var b = c(bottom)
        if (l + r > MAX_TOTAL) {
            val k = MAX_TOTAL / (l + r); l *= k; r *= k
        }
        if (t + b > MAX_TOTAL) {
            val k = MAX_TOTAL / (t + b); t *= k; b *= k
        }
        return Crop(l, t, r, b)
    }

    companion object {
        val NONE = Crop()
        const val MAX_EDGE = 0.9f
        const val MAX_TOTAL = 0.9f
    }
}

/**
 * Visual placement of a clip on the canvas. Position is in normalized canvas units where
 * `(0, 0)` is the centre, `x = 1` the right edge and `y = 1` the top edge. Positive rotation is
 * clockwise.
 */
data class Transform(
    val positionX: Float = 0f,
    val positionY: Float = 0f,
    val scale: Float = 1f,
    val rotationDegrees: Float = 0f,
    val opacity: Float = 1f,
    val crop: Crop = Crop.NONE,
    val fit: FitMode = FitMode.FIT,
    /** Mirror the clip left-right / top-bottom about its own centre. */
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
) {
    val isIdentityPlacement: Boolean
        get() = positionX == 0f && positionY == 0f && scale == 1f && rotationDegrees == 0f && !flipHorizontal && !flipVertical

    /** Clamps every value to its valid range; NaN and infinities become the defaults. */
    fun sanitized(): Transform = copy(
        positionX = positionX.finiteOr(0f).coerceIn(-MAX_OFFSET, MAX_OFFSET),
        positionY = positionY.finiteOr(0f).coerceIn(-MAX_OFFSET, MAX_OFFSET),
        scale = scale.finiteOr(1f).coerceIn(MIN_SCALE, MAX_SCALE),
        rotationDegrees = normalizeDegrees(rotationDegrees.finiteOr(0f)),
        opacity = opacity.finiteOr(1f).coerceIn(0f, 1f),
        crop = crop.sanitized(),
    )

    companion object {
        val DEFAULT = Transform()
        const val MIN_SCALE = 0.05f
        const val MAX_SCALE = 8f
        const val MAX_OFFSET = 3f

        fun normalizeDegrees(deg: Float): Float {
            var d = deg % 360f
            if (d > 180f) d -= 360f
            if (d <= -180f) d += 360f
            return d
        }
    }
}

/** Per-clip audio settings. [volume] is linear gain in `[0, 1]`. */
data class ClipAudio(
    val volume: Float = 1f,
    val muted: Boolean = false,
    val fadeInUs: Long = 0L,
    val fadeOutUs: Long = 0L,
) {
    val isSilent: Boolean get() = muted || volume <= 0f

    fun sanitized(): ClipAudio = copy(
        volume = volume.finiteOr(1f).coerceIn(0f, 1f),
        fadeInUs = fadeInUs.coerceAtLeast(0),
        fadeOutUs = fadeOutUs.coerceAtLeast(0),
    )

    companion object {
        val DEFAULT = ClipAudio()
    }
}

enum class TextAlignment { START, CENTER, END }

/**
 * Content and styling of a text clip. [sizeFraction] is the text height relative to the canvas
 * height, so text scales consistently between preview and export resolutions.
 */
data class TextStyle(
    val text: String,
    val sizeFraction: Float = 0.07f,
    val colorArgb: Int = 0xFFF5F5F7.toInt(),
    val backgroundArgb: Int = 0x00000000,
    val bold: Boolean = true,
    val alignment: TextAlignment = TextAlignment.CENTER,
    /** A built-in font id (see [FontIds]) or `custom:<file>` for an imported font. */
    val fontId: String = FontIds.DEFAULT,
    /** Outline width relative to the text size; 0 disables the outline. */
    val strokeWidth: Float = 0f,
    val strokeColorArgb: Int = 0xFF101116.toInt(),
    val shadow: Boolean = false,
) {
    fun sanitized(): TextStyle = copy(
        sizeFraction = sizeFraction.finiteOr(0.07f).coerceIn(0.01f, 0.5f),
        strokeWidth = strokeWidth.finiteOr(0f).coerceIn(0f, 0.3f),
    )
}

/** Identifiers of the fonts bundled with Clippr. Imported fonts use `custom:<file name>`. */
object FontIds {
    const val DEFAULT = "inter"
    const val CUSTOM_PREFIX = "custom:"

    /** Built-in font ids in display order, with display names. */
    val BUILT_IN: List<Pair<String, String>> = listOf(
        "inter" to "Inter",
        "montserrat" to "Montserrat",
        "oswald" to "Oswald",
        "bebas_neue" to "Bebas Neue",
        "anton" to "Anton",
        "playfair_display" to "Playfair Display",
        "abril_fatface" to "Abril Fatface",
        "lobster" to "Lobster",
        "pacifico" to "Pacifico",
        "dancing_script" to "Dancing Script",
        "caveat" to "Caveat",
        "jetbrains_mono" to "JetBrains Mono",
        "system_sans" to "System Sans",
        "system_serif" to "System Serif",
        "system_mono" to "System Mono",
    )

    fun isCustom(id: String): Boolean = id.startsWith(CUSTOM_PREFIX)
}

/** This value if it is a real number, otherwise [fallback] (guards against NaN and infinity). */
fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback
