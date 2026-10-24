package app.clippr.core.model.serialization

import app.clippr.core.model.Clip
import app.clippr.core.model.ColorGrade
import app.clippr.core.model.Easing
import app.clippr.core.model.FilterPreset
import app.clippr.core.model.FontIds
import app.clippr.core.model.TransformKeyframe
import app.clippr.core.model.VisualEffects
import app.clippr.core.model.VolumeKeyframe
import app.clippr.core.model.ClipAudio
import app.clippr.core.model.Crop
import app.clippr.core.model.FitMode
import app.clippr.core.model.MediaAsset
import app.clippr.core.model.MediaType
import app.clippr.core.model.Project
import app.clippr.core.model.ProjectSettings
import app.clippr.core.model.TextAlignment
import app.clippr.core.model.TextStyle
import app.clippr.core.model.TimelineSequence
import app.clippr.core.model.Track
import app.clippr.core.model.TrackKind
import app.clippr.core.model.Transform
import app.clippr.core.model.time.FrameRate

class ProjectFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Encodes projects as versioned JSON documents ("Clippr project format").
 *
 * Rules that keep old projects loadable:
 * - Every document carries [SCHEMA_VERSION]. Readers accept any version up to their own.
 * - Fields are only ever added. Missing fields fall back to the model defaults.
 * - Unknown enum values degrade to safe defaults rather than failing the whole project.
 */
object ProjectCodec {

    const val SCHEMA_VERSION = 1
    const val FORMAT = "clippr.project"

    fun encode(project: Project): String = Json.write(encodeProject(project))

    fun decode(text: String): Project {
        val root = try {
            Json.parse(text) as? JsonValue.Obj ?: throw ProjectFormatException("Root is not an object")
        } catch (e: JsonException) {
            throw ProjectFormatException("Invalid JSON: ${e.message}", e)
        }
        if (root.str("format") != FORMAT) throw ProjectFormatException("Not a Clippr project")
        val version = root.int("schemaVersion")
        if (version < 1 || version > SCHEMA_VERSION) {
            throw ProjectFormatException("Unsupported project version $version (this app supports up to $SCHEMA_VERSION)")
        }
        return try {
            decodeProject(root)
        } catch (e: ProjectFormatException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw ProjectFormatException("Corrupt project: ${e.message}", e)
        }
    }

    // Encoding -------------------------------------------------------------------------------

    private fun encodeProject(p: Project) = jsonObject(
        "format" to json(FORMAT),
        "schemaVersion" to json(SCHEMA_VERSION),
        "id" to json(p.id),
        "name" to json(p.name),
        "createdAtMs" to json(p.createdAtMs),
        "modifiedAtMs" to json(p.modifiedAtMs),
        "settings" to jsonObject(
            "width" to json(p.settings.width),
            "height" to json(p.settings.height),
            "fpsNum" to json(p.settings.frameRate.numerator),
            "fpsDen" to json(p.settings.frameRate.denominator),
        ),
        "assets" to jsonArray(p.assets.map(::encodeAsset)),
        "tracks" to jsonArray(p.sequence.tracks.map(::encodeTrack)),
    )

    private fun encodeAsset(a: MediaAsset) = jsonObject(
        "id" to json(a.id),
        "uri" to json(a.uri),
        "name" to json(a.displayName),
        "type" to json(a.type.name),
        "durationUs" to json(a.durationUs),
        "width" to json(a.width),
        "height" to json(a.height),
        "rotation" to json(a.rotationDegrees),
        "frameRate" to a.frameRate?.let { json(finite(it)) },
        "hasVideo" to json(a.hasVideo),
        "hasAudio" to json(a.hasAudio),
        "mime" to a.mimeType?.let { json(it) },
        "size" to json(a.sizeBytes),
    )

    private fun encodeTrack(t: Track) = jsonObject(
        "id" to json(t.id),
        "kind" to json(t.kind.name),
        "name" to json(t.name),
        "muted" to json(t.muted),
        "locked" to json(t.locked),
        "clips" to jsonArray(t.clips.map(::encodeClip)),
    )

    private fun encodeClip(c: Clip) = jsonObject(
        "id" to json(c.id),
        "assetId" to c.assetId?.let { json(it) },
        "start" to json(c.timelineStartUs),
        "in" to json(c.sourceStartUs),
        "out" to json(c.sourceEndUs),
        "speed" to json(finite(c.speed)),
        "transform" to encodeTransform(c.transform),
        "audio" to jsonObject(
            "volume" to json(finite(c.audio.volume)),
            "muted" to json(c.audio.muted),
            "fadeIn" to json(c.audio.fadeInUs),
            "fadeOut" to json(c.audio.fadeOutUs),
        ),
        "text" to c.text?.let { s ->
            jsonObject(
                "text" to json(s.text),
                "size" to json(finite(s.sizeFraction)),
                "color" to json(s.colorArgb),
                "background" to json(s.backgroundArgb),
                "bold" to json(s.bold),
                "align" to json(s.alignment.name),
                "font" to json(s.fontId),
                "stroke" to json(finite(s.strokeWidth)),
                "strokeColor" to json(s.strokeColorArgb),
                "shadow" to json(s.shadow),
            )
        },
        "keyframes" to if (c.keyframes.isEmpty()) null else jsonArray(c.keyframes.map(::encodeKeyframe)),
        "volumeKeys" to if (c.volumeKeyframes.isEmpty()) null else jsonArray(
            c.volumeKeyframes.map { jsonObject("t" to json(it.sourceUs), "v" to json(finite(it.volume)), "e" to json(it.easing.name)) },
        ),
        "color" to if (c.color == ColorGrade.NONE) null else encodeColor(c.color),
        "effects" to if (c.effects == VisualEffects.NONE) null else jsonObject(
            "blur" to json(finite(c.effects.blur)),
            "vignette" to json(finite(c.effects.vignette)),
            "grain" to json(finite(c.effects.grain)),
            "fadeIn" to json(c.effects.fadeInUs),
            "fadeOut" to json(c.effects.fadeOutUs),
        ),
    )

    private fun encodeKeyframe(k: TransformKeyframe) = jsonObject(
        "t" to json(k.sourceUs),
        "x" to json(finite(k.positionX)),
        "y" to json(finite(k.positionY)),
        "s" to json(finite(k.scale)),
        "r" to json(finite(k.rotationDegrees)),
        "o" to json(finite(k.opacity)),
        "e" to json(k.easing.name),
    )

    private fun encodeColor(g: ColorGrade) = jsonObject(
        "exposure" to json(finite(g.exposure)),
        "contrast" to json(finite(g.contrast)),
        "highlights" to json(finite(g.highlights)),
        "shadows" to json(finite(g.shadows)),
        "saturation" to json(finite(g.saturation)),
        "temperature" to json(finite(g.temperature)),
        "tint" to json(finite(g.tint)),
        "filter" to json(g.filter.name),
        "filterStrength" to json(finite(g.filterStrength)),
        "lut" to g.lutId?.let { json(it) },
    )

    private fun encodeTransform(t: Transform) = jsonObject(
        "x" to json(finite(t.positionX)),
        "y" to json(finite(t.positionY)),
        "scale" to json(finite(t.scale)),
        "rotation" to json(finite(t.rotationDegrees)),
        "opacity" to json(finite(t.opacity)),
        "fit" to json(t.fit.name),
        "crop" to jsonObject(
            "l" to json(finite(t.crop.left)),
            "t" to json(finite(t.crop.top)),
            "r" to json(finite(t.crop.right)),
            "b" to json(finite(t.crop.bottom)),
        ),
    )

    private fun finite(f: Float): Float = if (f.isFinite()) f else 0f

    // Decoding -------------------------------------------------------------------------------

    private fun decodeProject(o: JsonValue.Obj): Project {
        val settings = o.obj("settings") ?: throw ProjectFormatException("Missing settings")
        val tracks = o.arr("tracks").mapNotNull { (it as? JsonValue.Obj)?.let(::decodeTrack) }
        return Project(
            id = o.str("id") ?: throw ProjectFormatException("Missing id"),
            name = o.str("name") ?: "Untitled",
            settings = ProjectSettings(
                width = settings.int("width", 1920),
                height = settings.int("height", 1080),
                frameRate = FrameRate(settings.int("fpsNum", 30), settings.int("fpsDen", 1)),
            ),
            assets = o.arr("assets").mapNotNull { (it as? JsonValue.Obj)?.let(::decodeAsset) },
            sequence = if (tracks.isEmpty()) TimelineSequence.default() else TimelineSequence(tracks),
            createdAtMs = o.long("createdAtMs"),
            modifiedAtMs = o.long("modifiedAtMs"),
        )
    }

    private fun decodeAsset(o: JsonValue.Obj): MediaAsset? {
        val type = enumOrNull<MediaType>(o.str("type")) ?: return null
        return MediaAsset(
            id = o.str("id") ?: return null,
            uri = o.str("uri") ?: return null,
            displayName = o.str("name") ?: "",
            type = type,
            durationUs = o.long("durationUs"),
            width = o.int("width"),
            height = o.int("height"),
            rotationDegrees = o.int("rotation"),
            frameRate = o.floatOrNull("frameRate"),
            hasVideo = o.bool("hasVideo", type != MediaType.AUDIO),
            hasAudio = o.bool("hasAudio", type != MediaType.IMAGE),
            mimeType = o.str("mime"),
            sizeBytes = o.long("size"),
        )
    }

    private fun decodeTrack(o: JsonValue.Obj): Track? {
        val kind = enumOrNull<TrackKind>(o.str("kind")) ?: return null
        return Track(
            id = o.str("id") ?: return null,
            kind = kind,
            name = o.str("name") ?: kind.name.lowercase(),
            clips = o.arr("clips").mapNotNull { (it as? JsonValue.Obj)?.let(::decodeClip) }.sortedBy { it.timelineStartUs },
            muted = o.bool("muted"),
            locked = o.bool("locked"),
        )
    }

    private fun decodeClip(o: JsonValue.Obj): Clip? {
        val audio = o.obj("audio")
        val text = o.obj("text")
        return Clip(
            id = o.str("id") ?: return null,
            assetId = o.str("assetId"),
            timelineStartUs = o.long("start"),
            sourceStartUs = o.long("in"),
            sourceEndUs = o.long("out"),
            speed = o.float("speed", 1f).takeIf { it > 0f } ?: 1f,
            transform = o.obj("transform")?.let(::decodeTransform) ?: Transform.DEFAULT,
            audio = if (audio == null) {
                ClipAudio.DEFAULT
            } else {
                ClipAudio(
                    volume = audio.float("volume", 1f),
                    muted = audio.bool("muted"),
                    fadeInUs = audio.long("fadeIn"),
                    fadeOutUs = audio.long("fadeOut"),
                )
            },
            text = text?.let {
                TextStyle(
                    text = it.str("text") ?: "",
                    sizeFraction = it.float("size", 0.07f),
                    colorArgb = it.int("color", 0xFFF5F5F7.toInt()),
                    backgroundArgb = it.int("background", 0),
                    bold = it.bool("bold", true),
                    alignment = enumOrNull<TextAlignment>(it.str("align")) ?: TextAlignment.CENTER,
                    fontId = it.str("font") ?: FontIds.DEFAULT,
                    strokeWidth = it.float("stroke"),
                    strokeColorArgb = it.int("strokeColor", 0xFF101116.toInt()),
                    shadow = it.bool("shadow"),
                )
            },
            keyframes = o.arr("keyframes").mapNotNull { (it as? JsonValue.Obj)?.let(::decodeKeyframe) }.sortedBy { it.sourceUs },
            volumeKeyframes = o.arr("volumeKeys").mapNotNull { v ->
                (v as? JsonValue.Obj)?.let {
                    VolumeKeyframe(it.long("t"), it.float("v", 1f), enumOrNull<Easing>(it.str("e")) ?: Easing.LINEAR)
                }
            }.sortedBy { it.sourceUs },
            color = o.obj("color")?.let(::decodeColor) ?: ColorGrade.NONE,
            effects = o.obj("effects")?.let {
                VisualEffects(it.float("blur"), it.float("vignette"), it.float("grain"), it.long("fadeIn"), it.long("fadeOut")).sanitized()
            } ?: VisualEffects.NONE,
        )
    }

    private fun decodeKeyframe(o: JsonValue.Obj) = TransformKeyframe(
        sourceUs = o.long("t"),
        positionX = o.float("x"),
        positionY = o.float("y"),
        scale = o.float("s", 1f),
        rotationDegrees = o.float("r"),
        opacity = o.float("o", 1f),
        easing = enumOrNull<Easing>(o.str("e")) ?: Easing.EASE_IN_OUT,
    )

    private fun decodeColor(o: JsonValue.Obj) = ColorGrade(
        exposure = o.float("exposure"),
        contrast = o.float("contrast"),
        highlights = o.float("highlights"),
        shadows = o.float("shadows"),
        saturation = o.float("saturation"),
        temperature = o.float("temperature"),
        tint = o.float("tint"),
        filter = enumOrNull<FilterPreset>(o.str("filter")) ?: FilterPreset.NONE,
        filterStrength = o.float("filterStrength", 1f),
        lutId = o.str("lut"),
    ).sanitized()

    private fun decodeTransform(o: JsonValue.Obj): Transform {
        val crop = o.obj("crop")
        return Transform(
            positionX = o.float("x"),
            positionY = o.float("y"),
            scale = o.float("scale", 1f),
            rotationDegrees = o.float("rotation"),
            opacity = o.float("opacity", 1f),
            fit = enumOrNull<FitMode>(o.str("fit")) ?: FitMode.FIT,
            crop = if (crop == null) Crop.NONE else Crop(crop.float("l"), crop.float("t"), crop.float("r"), crop.float("b")),
        ).sanitized()
    }

    private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
        if (name == null) null else enumValues<E>().firstOrNull { it.name == name }
}
