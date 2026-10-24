package app.clippr.core.model

import kotlin.math.pow
import kotlin.math.roundToInt

/** Built-in looks, applied after the manual adjustments. */
enum class FilterPreset(val label: String) {
    NONE("None"),
    VIVID("Vivid"),
    WARM("Warm"),
    COOL("Cool"),
    TEAL_ORANGE("Teal & Orange"),
    FADED("Faded"),
    MONO("Mono"),
    NOIR("Noir"),
    SEPIA("Sepia"),
}

/**
 * Colour adjustments for a clip. Every value is centred on 0 (no change) and ranges `-1..1`,
 * except [exposure] (stops, `-2..2`) and [filterStrength] (`0..1`). [lutId] references an
 * imported `.cube` file by name; it is applied last.
 */
data class ColorGrade(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val saturation: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val filter: FilterPreset = FilterPreset.NONE,
    val filterStrength: Float = 1f,
    val lutId: String? = null,
) {
    val isIdentity: Boolean
        get() = exposure == 0f && contrast == 0f && highlights == 0f && shadows == 0f && saturation == 0f &&
            temperature == 0f && tint == 0f && (filter == FilterPreset.NONE || filterStrength == 0f) && lutId == null

    fun sanitized() = copy(
        exposure = exposure.coerceIn(-2f, 2f),
        contrast = contrast.coerceIn(-1f, 1f),
        highlights = highlights.coerceIn(-1f, 1f),
        shadows = shadows.coerceIn(-1f, 1f),
        saturation = saturation.coerceIn(-1f, 1f),
        temperature = temperature.coerceIn(-1f, 1f),
        tint = tint.coerceIn(-1f, 1f),
        filterStrength = filterStrength.coerceIn(0f, 1f),
    )

    companion object {
        val NONE = ColorGrade()
    }
}

/**
 * A 3D colour lookup table parsed from an Adobe/Resolve `.cube` file. Values are stored with red
 * varying fastest, as in the file format.
 */
class CubeLut(val size: Int, private val data: FloatArray, private val domainMin: FloatArray, private val domainMax: FloatArray) {

    init {
        require(size >= 2) { "LUT size must be at least 2" }
        require(data.size == size * size * size * 3) { "LUT data has ${data.size / 3} entries, expected ${size * size * size}" }
    }

    private fun at(r: Int, g: Int, b: Int, c: Int): Float = data[((b * size + g) * size + r) * 3 + c]

    /** Trilinearly samples the LUT for an input colour in `0..1`. */
    fun sample(r: Float, g: Float, b: Float, out: FloatArray) {
        val n = size - 1
        fun pos(v: Float, ch: Int): Float {
            val d = (domainMax[ch] - domainMin[ch]).takeIf { it > 0f } ?: 1f
            return ((v - domainMin[ch]) / d).coerceIn(0f, 1f) * n
        }
        val fr = pos(r, 0)
        val fg = pos(g, 1)
        val fb = pos(b, 2)
        val r0 = fr.toInt().coerceAtMost(n - 1)
        val g0 = fg.toInt().coerceAtMost(n - 1)
        val b0 = fb.toInt().coerceAtMost(n - 1)
        val dr = fr - r0
        val dg = fg - g0
        val db = fb - b0
        for (c in 0..2) {
            val c00 = at(r0, g0, b0, c) * (1 - dr) + at(r0 + 1, g0, b0, c) * dr
            val c10 = at(r0, g0 + 1, b0, c) * (1 - dr) + at(r0 + 1, g0 + 1, b0, c) * dr
            val c01 = at(r0, g0, b0 + 1, c) * (1 - dr) + at(r0 + 1, g0, b0 + 1, c) * dr
            val c11 = at(r0, g0 + 1, b0 + 1, c) * (1 - dr) + at(r0 + 1, g0 + 1, b0 + 1, c) * dr
            val c0 = c00 * (1 - dg) + c10 * dg
            val c1 = c01 * (1 - dg) + c11 * dg
            out[c] = c0 * (1 - db) + c1 * db
        }
    }

    companion object {
        /** Parses the text of a `.cube` file. Only 3D LUTs are supported. */
        fun parse(text: String): CubeLut {
            var size = 0
            val min = floatArrayOf(0f, 0f, 0f)
            val max = floatArrayOf(1f, 1f, 1f)
            val values = ArrayList<Float>()
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val parts = line.split(Regex("\\s+"))
                when (parts[0].uppercase()) {
                    "TITLE" -> Unit
                    "LUT_3D_SIZE" -> size = parts.getOrNull(1)?.toIntOrNull() ?: throw IllegalArgumentException("Bad LUT_3D_SIZE")
                    "LUT_1D_SIZE" -> throw IllegalArgumentException("1D LUTs are not supported")
                    "DOMAIN_MIN" -> for (i in 0..2) min[i] = parts[i + 1].toFloat()
                    "DOMAIN_MAX" -> for (i in 0..2) max[i] = parts[i + 1].toFloat()
                    else -> {
                        val first = parts[0].toFloatOrNull()
                        if (first == null) {
                            // Unknown keyword (e.g. LUT_3D_INPUT_RANGE): ignore it.
                        } else {
                            if (parts.size < 3) throw IllegalArgumentException("Unexpected line: $line")
                            val nums = parts.take(3).map { it.toFloatOrNull() ?: throw IllegalArgumentException("Unexpected line: $line") }
                            values.addAll(nums)
                        }
                    }
                }
            }
            if (size < 2) throw IllegalArgumentException("Missing LUT_3D_SIZE")
            if (size > 128) throw IllegalArgumentException("LUT size $size is too large")
            return CubeLut(size, values.toFloatArray(), min, max)
        }
    }
}

/**
 * Colour maths for [ColorGrade]. The renderer bakes the whole grade (plus an optional imported
 * LUT) into a single 3D lookup table, so any combination costs one texture lookup per pixel and
 * looks identical in preview and export. Operates on display-referred (gamma-encoded) values.
 */
object ColorMath {

    private const val LR = 0.2126f
    private const val LG = 0.7152f
    private const val LB = 0.0722f

    private fun luma(r: Float, g: Float, b: Float) = LR * r + LG * g + LB * b

    /** Grades one colour in place (`rgb` values in `0..1`). */
    fun apply(grade: ColorGrade, rgb: FloatArray, lut: CubeLut? = null, scratch: FloatArray = FloatArray(3)) {
        var r = rgb[0]
        var g = rgb[1]
        var b = rgb[2]

        // Exposure in stops.
        if (grade.exposure != 0f) {
            val k = 2f.pow(grade.exposure)
            r *= k; g *= k; b *= k
        }
        // White balance: temperature shifts blue↔orange, tint shifts green↔magenta.
        if (grade.temperature != 0f || grade.tint != 0f) {
            r *= 1f + 0.18f * grade.temperature + 0.06f * grade.tint
            g *= 1f - 0.12f * grade.tint
            b *= 1f - 0.18f * grade.temperature + 0.06f * grade.tint
        }
        // Shadows and highlights: luminance-weighted lifts that leave mid-tones mostly alone.
        if (grade.shadows != 0f || grade.highlights != 0f) {
            val l = luma(r, g, b).coerceIn(0f, 1f)
            val ws = (1f - l) * (1f - l)
            val wh = l * l
            val delta = 0.35f * (grade.shadows * ws + grade.highlights * wh)
            r += delta; g += delta; b += delta
        }
        // Contrast around mid-grey.
        if (grade.contrast != 0f) {
            val k = if (grade.contrast >= 0f) 1f + grade.contrast * 1.2f else 1f + grade.contrast * 0.8f
            r = 0.5f + (r - 0.5f) * k
            g = 0.5f + (g - 0.5f) * k
            b = 0.5f + (b - 0.5f) * k
        }
        // Saturation, preserving luminance.
        if (grade.saturation != 0f) {
            val l = luma(r, g, b)
            val k = 1f + grade.saturation
            r = l + (r - l) * k
            g = l + (g - l) * k
            b = l + (b - l) * k
        }
        r = r.coerceIn(0f, 1f); g = g.coerceIn(0f, 1f); b = b.coerceIn(0f, 1f)

        if (grade.filter != FilterPreset.NONE && grade.filterStrength > 0f) {
            scratch[0] = r; scratch[1] = g; scratch[2] = b
            filter(grade.filter, scratch)
            val s = grade.filterStrength
            r += (scratch[0] - r) * s
            g += (scratch[1] - g) * s
            b += (scratch[2] - b) * s
        }
        if (lut != null) {
            lut.sample(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), scratch)
            r = scratch[0]; g = scratch[1]; b = scratch[2]
        }
        rgb[0] = r.coerceIn(0f, 1f)
        rgb[1] = g.coerceIn(0f, 1f)
        rgb[2] = b.coerceIn(0f, 1f)
    }

    private fun filter(preset: FilterPreset, c: FloatArray) {
        val r = c[0]
        val g = c[1]
        val b = c[2]
        val l = luma(r, g, b)
        when (preset) {
            FilterPreset.NONE -> Unit
            FilterPreset.VIVID -> {
                c[0] = l + (r - l) * 1.35f; c[1] = l + (g - l) * 1.35f; c[2] = l + (b - l) * 1.35f
                for (i in 0..2) c[i] = 0.5f + (c[i] - 0.5f) * 1.08f
            }
            FilterPreset.WARM -> { c[0] = r * 1.08f + 0.02f; c[1] = g * 1.02f; c[2] = b * 0.88f }
            FilterPreset.COOL -> { c[0] = r * 0.9f; c[1] = g * 1.0f; c[2] = b * 1.1f + 0.02f }
            FilterPreset.TEAL_ORANGE -> {
                // Push shadows toward teal and highlights toward orange.
                val t = l
                c[0] = r + (0.10f * t - 0.06f * (1 - t))
                c[1] = g + (0.02f * t + 0.03f * (1 - t))
                c[2] = b + (-0.10f * t + 0.08f * (1 - t))
            }
            FilterPreset.FADED -> {
                for (i in 0..2) c[i] = 0.08f + c[i] * 0.84f
                val l2 = luma(c[0], c[1], c[2])
                for (i in 0..2) c[i] = l2 + (c[i] - l2) * 0.8f
            }
            FilterPreset.MONO -> { c[0] = l; c[1] = l; c[2] = l }
            FilterPreset.NOIR -> {
                val v = (0.5f + (l - 0.5f) * 1.45f).coerceIn(0f, 1f)
                c[0] = v; c[1] = v; c[2] = v
            }
            FilterPreset.SEPIA -> {
                c[0] = 0.393f * r + 0.769f * g + 0.189f * b
                c[1] = 0.349f * r + 0.686f * g + 0.168f * b
                c[2] = 0.272f * r + 0.534f * g + 0.131f * b
            }
        }
        for (i in 0..2) c[i] = c[i].coerceIn(0f, 1f)
    }

    /**
     * Bakes [grade] (and [lut]) into an `N x N x N` table of packed ARGB colours indexed
     * `[r][g][b]`, the layout Media3's `SingleColorLut.createFromCube` expects.
     */
    fun bakeLut(grade: ColorGrade, lut: CubeLut?, size: Int = 33): Array<Array<IntArray>> {
        val rgb = FloatArray(3)
        val scratch = FloatArray(3)
        val n = (size - 1).toFloat()
        return Array(size) { ri ->
            Array(size) { gi ->
                IntArray(size) { bi ->
                    rgb[0] = ri / n; rgb[1] = gi / n; rgb[2] = bi / n
                    apply(grade, rgb, lut, scratch)
                    pack(rgb)
                }
            }
        }
    }

    fun pack(rgb: FloatArray): Int {
        val r = (rgb[0] * 255f).roundToInt().coerceIn(0, 255)
        val g = (rgb[1] * 255f).roundToInt().coerceIn(0, 255)
        val b = (rgb[2] * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
