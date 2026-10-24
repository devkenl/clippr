package app.clippr.feature.home

import android.app.usage.StorageStatsManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.clippr.core.database.AppSettings
import app.clippr.core.database.ExportRecord
import app.clippr.core.database.ExportRepository
import app.clippr.core.database.ProjectRepository
import app.clippr.core.database.ProjectSummary
import app.clippr.core.database.SettingsValues
import app.clippr.core.media.ImportOutcomeMedia
import app.clippr.core.media.MediaImporter
import app.clippr.core.media.MediaStorage
import app.clippr.core.media.ThumbnailRepository
import app.clippr.core.media.VideoLibrary
import app.clippr.core.model.AspectRatioPreset
import app.clippr.core.model.MediaAsset
import app.clippr.core.model.MediaType
import app.clippr.core.model.ProjectSettings
import app.clippr.core.model.ProjectTemplate
import app.clippr.core.model.edit.AddAssetClip
import app.clippr.core.model.edit.ImportAssets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Everything Home needs from the app; created once by the app container. */
class HomeDependencies(
    val context: Context,
    val projects: ProjectRepository,
    val exports: ExportRepository,
    val settings: AppSettings,
    val importer: MediaImporter,
    val videoLibrary: VideoLibrary,
    val thumbnails: ThumbnailRepository,
)

/** Device storage and what Clippr itself uses. All sizes in bytes. */
data class StorageInfo(
    val totalBytes: Long,
    val freeBytes: Long,
    /** Exported videos in Movies/Clippr that Clippr knows about. */
    val exportsBytes: Long,
    /** Files copied from cloud apps, voiceovers and freeze-frame stills. */
    val mediaBytes: Long,
    /** Thumbnails and waveforms; safe to clear. */
    val cacheBytes: Long,
    /** Imported fonts and LUTs. */
    val resourcesBytes: Long,
    /** Private media no project uses any more; null until computed. */
    val unusedBytes: Long? = null,
) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)
    val clipprBytes: Long get() = exportsBytes + mediaBytes + cacheBytes + resourcesBytes
}

/** A media file and the projects that use it (the Media tab). */
data class LibraryItem(val asset: MediaAsset, val projects: List<Pair<String, String>>)

data class HomeUiState(
    val loading: Boolean = true,
    val projects: List<ProjectSummary> = emptyList(),
    val exports: List<ExportRecord> = emptyList(),
    /** Exports whose video was deleted outside Clippr. */
    val missingExportIds: Set<String> = emptySet(),
    val storage: StorageInfo? = null,
    val settings: SettingsValues = SettingsValues(),
    /** Progress text while a quick action works (importing, creating from a template). */
    val busy: String? = null,
    val message: String? = null,
    /** Null until the Media tab is first opened. */
    val library: List<LibraryItem>? = null,
)

class HomeViewModel(private val deps: HomeDependencies) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState(settings = deps.settings.current))
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    val thumbnails: ThumbnailRepository get() = deps.thumbnails
    val videoLibrary: VideoLibrary get() = deps.videoLibrary

    init {
        viewModelScope.launch {
            deps.projects.observeProjects().collect { list -> _state.update { it.copy(loading = false, projects = list) } }
        }
        viewModelScope.launch {
            deps.exports.observeExports().collect { list ->
                _state.update { it.copy(exports = list) }
                refreshExportAvailability(list)
                refreshStorage()
            }
        }
        viewModelScope.launch {
            deps.settings.values.collect { v -> _state.update { it.copy(settings = v) } }
        }
    }

    // Projects ---------------------------------------------------------------------------------

    /** Creates a project and reports its id so the caller can open the editor. */
    fun create(name: String, settings: ProjectSettings, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val project = deps.projects.create(name, settings)
            onCreated(project.id)
        }
    }

    fun createFromTemplate(template: ProjectTemplate, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = "Creating “${template.name}”…") }
            val project = deps.projects.create(template.name, template.settings) { empty -> template.applyTo(empty, ::newId) }
            _state.update { it.copy(busy = null) }
            onCreated(project.id)
        }
    }

    /**
     * Quick action "Import media": imports the picked files into a new project, lays them out on
     * the timeline and opens it. Portrait footage gets a 9:16 canvas.
     */
    fun importToNewProject(uris: List<Uri>, onCreated: (String) -> Unit) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val outcomes = deps.importer.import(uris) { p ->
                val pct = p.copiedFraction?.let { " ${(it * 100).toInt()}%" } ?: ""
                val verb = if (p.copiedFraction != null) "Copying" else "Importing"
                _state.update { it.copy(busy = "$verb ${p.displayName}$pct (${p.index + 1}/${p.total})") }
            }
            val assets = outcomes.filterIsInstance<ImportOutcomeMedia.Imported>().map { it.asset }
            val failures = outcomes.filterIsInstance<ImportOutcomeMedia.Failed>()
            if (assets.isEmpty()) {
                _state.update { it.copy(busy = null, message = "Couldn't import: ${failures.joinToString("; ") { f -> "${f.displayName}: ${f.reason}" }}") }
                return@launch
            }
            val defaults = deps.settings.current
            val firstVisual = assets.firstOrNull { it.type != MediaType.AUDIO }
            val portrait = firstVisual != null && firstVisual.displayHeight > firstVisual.displayWidth
            val settings = if (portrait) {
                ProjectSettings.create(AspectRatioPreset.PORTRAIT_9_16, defaults.defaultShortSide, defaults.defaultFrameRate)
            } else {
                defaults.newProjectSettings()
            }
            val name = assets.first().displayName.substringBeforeLast('.').take(40).ifBlank { "Imported " + stamp() }
            val project = deps.projects.create(name, settings) { empty ->
                var p = ImportAssets(assets).apply(empty)
                for (a in assets) p = AddAssetClip(a.id, newId("clip")).apply(p)
                p
            }
            _state.update {
                it.copy(
                    busy = null,
                    message = if (failures.isEmpty()) null else "${failures.size} file${if (failures.size == 1) "" else "s"} couldn't be imported.",
                )
            }
            onCreated(project.id)
        }
    }

    fun rename(id: String, name: String) {
        viewModelScope.launch { deps.projects.rename(id, name) }
    }

    fun duplicate(id: String) {
        viewModelScope.launch { deps.projects.duplicate(id) }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            deps.projects.delete(id)
            refreshStorage()
        }
    }

    // Exports ----------------------------------------------------------------------------------

    fun removeExport(id: String) {
        viewModelScope.launch { deps.exports.remove(id) }
    }

    private suspend fun refreshExportAvailability(list: List<ExportRecord>) {
        val missing = list.filterNot { deps.videoLibrary.exists(Uri.parse(it.uri)) }.map { it.id }.toSet()
        _state.update { it.copy(missingExportIds = missing) }
    }

    // Media library ----------------------------------------------------------------------------

    fun loadLibrary() {
        viewModelScope.launch {
            val projects = deps.projects.loadAll()
            val byUri = LinkedHashMap<String, Pair<MediaAsset, MutableList<Pair<String, String>>>>()
            for (p in projects) for (a in p.assets) {
                // Freeze-frame stills are internal; list what the user imported or recorded.
                if (a.uri.contains("/frames/")) continue
                val entry = byUri.getOrPut(a.uri) { a to ArrayList() }
                if (entry.second.none { it.first == p.id }) entry.second += p.id to p.name
            }
            _state.update { it.copy(library = byUri.values.map { (a, ps) -> LibraryItem(a, ps) }) }
        }
    }

    // Storage ----------------------------------------------------------------------------------

    fun refreshStorage() {
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { measureStorage() }
            _state.update { s -> s.copy(storage = info.copy(unusedBytes = s.storage?.unusedBytes)) }
        }
    }

    /** Finds private media that no project references any more. */
    fun findUnusedMedia() {
        viewModelScope.launch {
            val unused = unusedFiles()
            _state.update { s -> s.copy(storage = s.storage?.copy(unusedBytes = unused.sumOf { it.length() })) }
        }
    }

    fun removeUnusedMedia() {
        viewModelScope.launch {
            val unused = unusedFiles()
            val removed = withContext(Dispatchers.IO) { unused.count { it.delete() } }
            _state.update { s ->
                s.copy(
                    message = "Removed $removed unused file${if (removed == 1) "" else "s"}.",
                    storage = s.storage?.copy(unusedBytes = 0L),
                )
            }
            refreshStorage()
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                MediaStorage.thumbnailCacheDir(deps.context).listFiles()?.forEach { it.deleteRecursively() }
                MediaStorage.waveformCacheDir(deps.context).listFiles()?.forEach { it.deleteRecursively() }
            }
            deps.thumbnails.trimMemory()
            _state.update { it.copy(message = "Cache cleared. Thumbnails and waveforms are rebuilt when needed.") }
            refreshStorage()
        }
    }

    private suspend fun unusedFiles(): List<File> {
        val used = deps.projects.loadAll().flatMap { p -> p.assets.mapNotNull { Uri.parse(it.uri).path } }.toSet()
        return withContext(Dispatchers.IO) {
            listOf(MediaStorage.importsDir(deps.context), MediaStorage.voiceoverDir(deps.context), MediaStorage.framesDir(deps.context))
                .flatMap { dir -> dir.listFiles()?.toList().orEmpty() }
                .filter { it.isFile && it.absolutePath !in used }
        }
    }

    private suspend fun measureStorage(): StorageInfo {
        val ctx = deps.context
        val (total, free) = try {
            val stats = ctx.getSystemService(StorageStatsManager::class.java)
            stats.getTotalBytes(StorageManager.UUID_DEFAULT) to stats.getFreeBytes(StorageManager.UUID_DEFAULT)
        } catch (e: Exception) {
            val fs = StatFs(Environment.getDataDirectory().path)
            fs.totalBytes to fs.availableBytes
        }
        val exportsBytes = _state.value.exports.filter { it.id !in _state.value.missingExportIds }.sumOf { it.sizeBytes }
        val media = MediaStorage.sizeOf(MediaStorage.importsDir(ctx)) + MediaStorage.sizeOf(MediaStorage.voiceoverDir(ctx)) +
            MediaStorage.sizeOf(MediaStorage.framesDir(ctx))
        val cache = MediaStorage.sizeOf(MediaStorage.thumbnailCacheDir(ctx)) + MediaStorage.sizeOf(MediaStorage.waveformCacheDir(ctx))
        val resources = MediaStorage.sizeOf(File(ctx.filesDir, "fonts")) + MediaStorage.sizeOf(File(ctx.filesDir, "luts"))
        return StorageInfo(total, free, exportsBytes, media, cache, resources)
    }

    // Settings ---------------------------------------------------------------------------------

    fun updateSettings(transform: (SettingsValues) -> SettingsValues) = deps.settings.update(transform)

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun newId(prefix: String) = "$prefix-${UUID.randomUUID()}"

    private fun stamp() = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date())
}
