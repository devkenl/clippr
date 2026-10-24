package app.clippr.core.render

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import app.clippr.core.model.ColorGrade
import app.clippr.core.model.ColorMath
import app.clippr.core.model.CubeLut
import app.clippr.core.model.FontIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Fonts and LUTs shared by the editor, preview and export. One instance per process. */
class RenderResources private constructor(context: Context) {
    val fonts = FontLibrary(context.applicationContext)
    val luts = LutLibrary(context.applicationContext)

    private val bakedLuts = object : LinkedHashMap<ColorGrade, Array<Array<IntArray>>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ColorGrade, Array<Array<IntArray>>>?) = size > 12
    }

    /** The grade (and its imported LUT, if still available) baked into a 3D lookup table. */
    fun bakedLut(grade: ColorGrade): Array<Array<IntArray>> = synchronized(bakedLuts) {
        bakedLuts.getOrPut(grade) { ColorMath.bakeLut(grade, grade.lutId?.let { luts.load(it) }) }
    }

    companion object {
        @Volatile private var instance: RenderResources? = null

        fun get(context: Context): RenderResources =
            instance ?: synchronized(this) { instance ?: RenderResources(context).also { instance = it } }
    }
}

/** Result of importing a user file (font or LUT). */
sealed interface ImportOutcome {
    data class Imported(val id: String, val name: String) : ImportOutcome
    data class Failed(val reason: String) : ImportOutcome
}

/**
 * Bundled fonts (SIL Open Font License, see assets/fonts/licenses) plus fonts the user imports.
 * Imported fonts are copied into app storage so projects keep rendering even if the original
 * file is moved.
 */
class FontLibrary(private val context: Context) {

    data class FontInfo(val id: String, val name: String, val isCustom: Boolean)

    private val customDir = File(context.filesDir, "fonts").apply { mkdirs() }
    private val cache = HashMap<String, Typeface>()
    private val _version = MutableStateFlow(0)

    /** Changes whenever the set of fonts changes. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun fonts(): List<FontInfo> {
        val builtIn = FontIds.BUILT_IN.map { (id, name) -> FontInfo(id, name, false) }
        val custom = customDir.listFiles { f -> f.extension.lowercase() in setOf("ttf", "otf") }.orEmpty()
            .sortedBy { it.lastModified() }
            .map { f -> FontInfo(FontIds.CUSTOM_PREFIX + f.name, nameOf(f), true) }
        return builtIn + custom
    }

    fun displayName(id: String): String = fonts().firstOrNull { it.id == id }?.name ?: "Missing font"

    /** Resolves a font id; unknown or missing fonts fall back to the default font. */
    fun typeface(id: String, bold: Boolean): Typeface = synchronized(cache) {
        cache.getOrPut("$id|$bold") { load(id, bold) ?: load(FontIds.DEFAULT, bold) ?: Typeface.DEFAULT }
    }

    /** Whether [typeface] already renders bold for [id] (so fake bold must not be added). */
    fun hasRealBold(id: String): Boolean = id in VARIABLE_WEIGHT || id.startsWith("system_")

    private fun load(id: String, bold: Boolean): Typeface? = try {
        when {
            id == "system_sans" -> Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
            id == "system_serif" -> Typeface.create(Typeface.SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
            id == "system_mono" -> Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            FontIds.isCustom(id) -> {
                val file = File(customDir, id.removePrefix(FontIds.CUSTOM_PREFIX))
                if (file.exists()) Typeface.Builder(file).build() else null
            }
            FontIds.BUILT_IN.any { it.first == id } -> {
                val builder = Typeface.Builder(context.assets, "fonts/$id.ttf")
                if (id in VARIABLE_WEIGHT) builder.setFontVariationSettings("'wght' ${if (bold) 700 else 400}")
                builder.build()
            }
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    /** Copies a font file the user picked into app storage. */
    suspend fun import(uri: Uri): ImportOutcome = withContext(Dispatchers.IO) {
        try {
            val name = displayNameOf(uri)
            val ext = name.substringAfterLast('.', "ttf").lowercase().takeIf { it == "ttf" || it == "otf" } ?: "ttf"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext ImportOutcome.Failed("The file could not be read")
            if (bytes.size > MAX_FONT_BYTES) return@withContext ImportOutcome.Failed("Font files larger than 20 MB are not supported")
            val file = File(customDir, sha1(bytes) + "." + ext)
            file.writeBytes(bytes)
            val ok = try {
                Typeface.Builder(file).build() != null
            } catch (e: Exception) {
                false
            }
            if (!ok) {
                file.delete()
                return@withContext ImportOutcome.Failed("This is not a font Android can read (use .ttf or .otf)")
            }
            File(customDir, file.name + ".name").writeText(name.substringBeforeLast('.'))
            synchronized(cache) { cache.keys.removeAll { it.startsWith(FontIds.CUSTOM_PREFIX + file.name) } }
            _version.value++
            ImportOutcome.Imported(FontIds.CUSTOM_PREFIX + file.name, name.substringBeforeLast('.'))
        } catch (e: Exception) {
            ImportOutcome.Failed(e.message ?: "Import failed")
        }
    }

    private fun nameOf(file: File): String =
        File(customDir, file.name + ".name").takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null } ?: file.nameWithoutExtension

    private fun displayNameOf(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
        }
        return uri.lastPathSegment ?: "Font"
    }

    private companion object {
        const val MAX_FONT_BYTES = 20 * 1024 * 1024
        val VARIABLE_WEIGHT = setOf("inter", "montserrat", "oswald", "playfair_display", "dancing_script", "jetbrains_mono", "caveat")
    }
}

/** Imported `.cube` LUTs, stored in app storage and referenced from clips by file name. */
class LutLibrary(private val context: Context) {

    private val dir = File(context.filesDir, "luts").apply { mkdirs() }
    private val cache = HashMap<String, CubeLut>()
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun list(): List<String> = dir.listFiles { f -> f.extension.equals("cube", true) }.orEmpty().map { it.name }.sorted()

    fun exists(id: String): Boolean = File(dir, id).exists()

    fun load(id: String): CubeLut? = synchronized(cache) {
        cache[id] ?: try {
            File(dir, id).takeIf { it.exists() }?.readText()?.let(CubeLut::parse)?.also { cache[id] = it }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun import(uri: Uri): ImportOutcome = withContext(Dispatchers.IO) {
        try {
            var name = "lut.cube"
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) name = c.getString(0)
            }
            val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?.takeIf { it.size <= 64 * 1024 * 1024 }?.toString(Charsets.UTF_8)
                ?: return@withContext ImportOutcome.Failed("The file could not be read")
            try {
                CubeLut.parse(text)
            } catch (e: IllegalArgumentException) {
                return@withContext ImportOutcome.Failed("Not a valid 3D .cube LUT: ${e.message}")
            }
            val safe = name.replace(Regex("[^A-Za-z0-9 ._-]"), "_").removeSuffix(".cube").removeSuffix(".CUBE").take(60) + ".cube"
            File(dir, safe).writeText(text)
            synchronized(cache) { cache.remove(safe) }
            _version.value++
            ImportOutcome.Imported(safe, safe.removeSuffix(".cube"))
        } catch (e: Exception) {
            ImportOutcome.Failed(e.message ?: "Import failed")
        }
    }
}

private fun sha1(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)
