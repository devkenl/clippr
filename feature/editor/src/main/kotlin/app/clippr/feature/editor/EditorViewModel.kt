package app.clippr.feature.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.clippr.core.database.LoadResult
import app.clippr.core.database.ProjectRepository
import app.clippr.core.media.FrameGrabber
import app.clippr.core.media.MediaAccess
import app.clippr.core.media.MediaProbe
import app.clippr.core.media.ProbeResult
import app.clippr.core.media.ThumbnailRepository
import app.clippr.core.media.WaveformRepository
import app.clippr.core.model.Animation
import app.clippr.core.model.Clip
import app.clippr.core.model.MediaAsset
import app.clippr.core.model.MediaType
import app.clippr.core.model.Project
import app.clippr.core.model.ProjectIntegrity
import app.clippr.core.model.ProjectSettings
import app.clippr.core.model.TextStyle
import app.clippr.core.model.TrackKind
import app.clippr.core.model.Transform
import app.clippr.core.model.time.US_PER_SECOND
import app.clippr.core.model.edit.AddAssetClip
import app.clippr.core.model.edit.AddTextClip
import app.clippr.core.model.edit.AddTrack
import app.clippr.core.model.edit.ChangeProjectSettings
import app.clippr.core.model.edit.CompositeCommand
import app.clippr.core.model.edit.DEFAULT_STILL_DURATION_US
import app.clippr.core.model.edit.DeleteClips
import app.clippr.core.model.edit.DuplicateClip
import app.clippr.core.model.edit.EditCommand
import app.clippr.core.model.edit.EditHistory
import app.clippr.core.model.edit.ImportAssets
import app.clippr.core.model.edit.RelinkAsset
import app.clippr.core.model.edit.RenameProject
import app.clippr.core.model.edit.SetClipSpeed
import app.clippr.core.model.edit.SetTrackLocked
import app.clippr.core.model.edit.SetTrackMuted
import app.clippr.core.model.edit.SplitClip
import app.clippr.core.model.edit.UpdateClip
import app.clippr.core.render.FontLibrary
import app.clippr.core.render.ImportOutcome
import app.clippr.core.render.PreviewEngine
import app.clippr.core.render.RenderResources
import app.clippr.core.render.PreviewQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Everything the editor needs from the app; created once by the app container. */
class EditorDependencies(
    val context: Context,
    val repository: ProjectRepository,
    val probe: MediaProbe,
    val access: MediaAccess,
    val thumbnails: ThumbnailRepository,
    val waveforms: WaveformRepository,
    /** Outlives screens, so the final save still completes when the editor closes. */
    val appScope: CoroutineScope,
    val resources: RenderResources,
    val frameGrabber: FrameGrabber,
)

enum class SaveStatus { SAVED, UNSAVED, SAVING, FAILED }

data class EditorUiState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val project: Project? = null,
    val selectedClipIds: Set<String> = emptySet(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val undoLabel: String? = null,
    val redoLabel: String? = null,
    val saveStatus: SaveStatus = SaveStatus.SAVED,
    val missingAssetIds: Set<String> = emptySet(),
    val importing: Boolean = false,
    val snapping: Boolean = true,
    val previewQuality: PreviewQuality = PreviewQuality.BALANCED,
    val message: String? = null,
    /** Asset ids imported in this session, highlighted in the media panel. */
    val recentAssetIds: Set<String> = emptySet(),
    val fonts: List<FontLibrary.FontInfo> = emptyList(),
    val luts: List<String> = emptyList(),
) {
    val selectedClip: Clip? get() = selectedClipIds.singleOrNull()?.let { project?.sequence?.clip(it) }
}

class EditorViewModel(
    private val projectId: String,
    private val deps: EditorDependencies,
) : ViewModel() {

    val preview = PreviewEngine(deps.context)
    val thumbnails: ThumbnailRepository get() = deps.thumbnails
    val waveforms: WaveformRepository get() = deps.waveforms

    private var history: EditHistory? = null
    private var liveCommand: EditCommand? = null
    private var liveProject: Project? = null
    private var saveJob: Job? = null
    private var previewJob: Job? = null
    private var lastThumbnailKey: String? = null

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    val resources: RenderResources get() = deps.resources

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch {
            deps.resources.fonts.version.collect { _state.update { it.copy(fonts = deps.resources.fonts.fonts()) } }
        }
        viewModelScope.launch {
            deps.resources.luts.version.collect { _state.update { it.copy(luts = deps.resources.luts.list()) } }
        }
    }

    private suspend fun load() {
        when (val result = deps.repository.load(projectId)) {
            is LoadResult.Loaded -> {
                val problems = ProjectIntegrity.problems(result.project)
                history = EditHistory(result.project)
                _state.update { it.copy(loading = false) }
                publish(previewNow = true)
                if (problems.isNotEmpty()) {
                    showMessage("This project has ${problems.size} inconsistencies. Affected clips may render as gaps.")
                }
                refreshMissingMedia()
            }
            LoadResult.NotFound -> _state.update { it.copy(loading = false, loadError = "This project no longer exists.") }
            is LoadResult.Unreadable -> _state.update {
                it.copy(loading = false, loadError = "This project could not be opened (${result.reason}). It has not been modified.")
            }
        }
    }

    /** The project as currently displayed, including an uncommitted live change. */
    private val displayed: Project? get() = liveProject ?: history?.current

    // -----------------------------------------------------------------------------------------
    // Editing
    // -----------------------------------------------------------------------------------------

    /** Runs [command] as one undoable step. Returns false if it changed nothing. */
    fun execute(command: EditCommand): Boolean {
        val h = history ?: return false
        commitLive()
        if (!h.execute(command)) return false
        onProjectChanged()
        return true
    }

    /** Previews [update] on [clipId] without recording history (e.g. while dragging a slider). */
    fun liveUpdate(clipId: String, label: String, update: (Clip) -> Clip) {
        val h = history ?: return
        val command = UpdateClip(clipId, label, update)
        liveCommand = command
        liveProject = command.apply(h.current)
        publish(previewNow = false)
    }

    /**
     * Previews new placement values for [clipId] from on-canvas gestures. With keyframes, the
     * keyframe at the playhead is updated (or created) instead of the static transform.
     */
    fun liveTransform(clipId: String, t: Transform) {
        val clip = history?.current?.sequence?.clip(clipId) ?: return
        val src = clip.sourceTimeAt(preview.state.value.positionUs.coerceIn(clip.timelineStartUs, clip.timelineEndUs - 1))
        liveUpdate(clipId, "Transform") { current ->
            Animation.editTransformAt(current, src, keyframeTolerance()) { base ->
                base.copy(positionX = t.positionX, positionY = t.positionY, scale = t.scale, rotationDegrees = t.rotationDegrees, opacity = t.opacity)
            }
        }
    }

    private fun keyframeTolerance(): Long = (history?.current?.settings?.frameRate?.frameDurationUs ?: 33_334L) / 2 + 1

    /** Records the pending live change, if any, as a single undo step. */
    fun commitLive() {
        val command = liveCommand ?: return
        liveCommand = null
        liveProject = null
        val h = history ?: return
        if (h.execute(command)) onProjectChanged() else publish(previewNow = true)
    }

    fun undo() {
        commitLive()
        val label = history?.undo() ?: return
        onProjectChanged()
        showMessage("Undid: $label")
    }

    fun redo() {
        commitLive()
        val label = history?.redo() ?: return
        onProjectChanged()
        showMessage("Redid: $label")
    }

    private fun onProjectChanged() {
        val project = history?.current ?: return
        // Drop selections of clips that no longer exist.
        _state.update { s -> s.copy(selectedClipIds = s.selectedClipIds.filter { project.sequence.clip(it) != null }.toSet()) }
        publish(previewNow = true)
        scheduleSave()
    }

    private fun publish(previewNow: Boolean) {
        val h = history ?: return
        val project = displayed ?: return
        _state.update {
            it.copy(
                project = project,
                canUndo = h.canUndo,
                canRedo = h.canRedo,
                undoLabel = h.undoLabel,
                redoLabel = h.redoLabel,
            )
        }
        previewJob?.cancel()
        if (previewNow) {
            preview.setProject(project, _state.value.previewQuality)
        } else {
            // Rebuilding the player is expensive; wait until a continuous gesture pauses.
            previewJob = viewModelScope.launch {
                delay(LIVE_PREVIEW_DEBOUNCE_MS)
                displayed?.let { preview.setProject(it, _state.value.previewQuality) }
            }
        }
    }

    fun select(clipId: String?, additive: Boolean = false) {
        commitLive()
        _state.update { s ->
            val next = when {
                clipId == null -> emptySet()
                additive -> if (clipId in s.selectedClipIds) s.selectedClipIds - clipId else s.selectedClipIds + clipId
                else -> setOf(clipId)
            }
            s.copy(selectedClipIds = next)
        }
    }

    fun splitAtPlayhead() {
        val project = history?.current ?: return
        val t = currentFrame()
        val selected = _state.value.selectedClipIds.mapNotNull { project.sequence.clip(it) }.filter { it.contains(t) }
        val targets = selected.ifEmpty {
            val main = project.sequence.mainVideoTrack?.takeIf { !it.locked }?.clipAt(t)
            listOfNotNull(main ?: project.sequence.tracks.firstOrNull { !it.locked && it.clipAt(t) != null }?.clipAt(t))
        }
        if (targets.isEmpty()) {
            showMessage("Move the playhead over a clip to split it.")
            return
        }
        val ok = execute(CompositeCommand("Split clip", targets.map { SplitClip(it.id, t, newId("clip")) }))
        if (!ok) showMessage("Can't split this close to the edge of a clip.")
    }

    fun splitClip(clipId: String) {
        val project = history?.current ?: return
        val clip = project.sequence.clip(clipId) ?: return
        val t = currentFrame()
        if (!clip.contains(t)) {
            showMessage("Move the playhead over the clip to split it there.")
            return
        }
        if (!execute(SplitClip(clipId, t, newId("clip")))) showMessage("Can't split this close to the edge of a clip.")
    }

    fun deleteClip(clipId: String, ripple: Boolean) {
        if (execute(DeleteClips(setOf(clipId), ripple))) {
            _state.update { it.copy(selectedClipIds = it.selectedClipIds - clipId) }
        }
    }

    fun duplicateClip(clipId: String) {
        val newClipId = newId("clip")
        if (execute(DuplicateClip(clipId, newClipId))) {
            _state.update { it.copy(selectedClipIds = setOf(newClipId)) }
        } else {
            showMessage("This clip can't be duplicated (is its track locked?).")
        }
    }

    /**
     * Freeze frame: grabs the frame under the playhead, splits the clip there and inserts a
     * 2-second still of that frame between the two halves.
     */
    fun freezeFrame(clipId: String? = null) {
        val project = history?.current ?: return
        val t = currentFrame()
        val clip = (clipId?.let { project.sequence.clip(it) } ?: project.sequence.mainVideoTrack?.clipAt(t))
        val track = clip?.let { project.sequence.trackOfClip(it.id) }
        val asset = project.asset(clip?.assetId)
        if (clip == null || track == null || asset == null || asset.type != MediaType.VIDEO || !clip.contains(t)) {
            showMessage("Put the playhead over a video clip to freeze a frame.")
            return
        }
        if (track.locked) {
            showMessage("Unlock the track to add a freeze frame.")
            return
        }
        viewModelScope.launch {
            val still = deps.frameGrabber.grab(asset, clip.sourceTimeAt(t), maxOf(project.settings.width, project.settings.height))
            if (still == null) {
                showMessage("Couldn't read that frame.")
                return@launch
            }
            val stillClipId = newId("clip")
            val commands = buildList<EditCommand> {
                add(ImportAssets(listOf(still)))
                if (t - clip.timelineStartUs > 100_000 && clip.timelineEndUs - t > 100_000) add(SplitClip(clip.id, t, newId("clip")))
                add(AddAssetClip(still.id, stillClipId, track.id, startUs = t, stillDurationUs = 2 * US_PER_SECOND))
                add(UpdateClip(stillClipId, "Freeze frame") { it.copy(transform = clip.transform, color = clip.color, effects = clip.effects.copy(fadeInUs = 0, fadeOutUs = 0)) })
            }
            if (execute(CompositeCommand("Freeze frame", commands))) {
                _state.update { it.copy(selectedClipIds = setOf(stillClipId)) }
            }
        }
    }

    fun importFont(uri: Uri, applyToClipId: String?) {
        viewModelScope.launch {
            when (val r = deps.resources.fonts.import(uri)) {
                is ImportOutcome.Imported -> {
                    showMessage("Added font “${r.name}”.")
                    if (applyToClipId != null) {
                        execute(UpdateClip(applyToClipId, "Change font") { it.copy(text = it.text?.copy(fontId = r.id)) })
                    }
                }
                is ImportOutcome.Failed -> showMessage("Couldn't import the font: ${r.reason}")
            }
        }
    }

    fun importLut(uri: Uri, applyToClipId: String?) {
        viewModelScope.launch {
            when (val r = deps.resources.luts.import(uri)) {
                is ImportOutcome.Imported -> {
                    showMessage("Added LUT “${r.name}”.")
                    if (applyToClipId != null) {
                        execute(UpdateClip(applyToClipId, "Apply LUT") { it.copy(color = it.color.copy(lutId = r.id)) })
                    }
                }
                is ImportOutcome.Failed -> showMessage("Couldn't import the LUT: ${r.reason}")
            }
        }
    }

    fun deleteSelection(ripple: Boolean = false) {
        val ids = _state.value.selectedClipIds
        if (ids.isEmpty()) return
        if (execute(DeleteClips(ids, ripple))) _state.update { it.copy(selectedClipIds = emptySet()) }
    }

    /** Places [asset] at the playhead: visuals on the main video track, audio on a free audio track. */
    fun addToTimeline(asset: MediaAsset) {
        val project = history?.current ?: return
        val t = currentFrame()
        val clipId = newId("clip")
        val command: EditCommand = when (asset.type) {
            MediaType.VIDEO, MediaType.IMAGE -> AddAssetClip(asset.id, clipId, project.sequence.mainVideoTrack?.id, startUs = t)
            MediaType.AUDIO -> {
                val duration = asset.durationUs
                val free = project.sequence.tracks.firstOrNull { track ->
                    track.kind == TrackKind.AUDIO && !track.locked &&
                        track.clips.none { it.timelineStartUs < t + duration && t < it.timelineEndUs }
                }
                if (free != null) {
                    AddAssetClip(asset.id, clipId, free.id, startUs = t)
                } else {
                    val trackId = newId("track")
                    CompositeCommand("Add clip", listOf(AddTrack(TrackKind.AUDIO, trackId), AddAssetClip(asset.id, clipId, trackId, startUs = t)))
                }
            }
        }
        if (execute(command)) {
            _state.update { it.copy(selectedClipIds = setOf(clipId)) }
        } else {
            showMessage("This media can't be placed on an unlocked track.")
        }
    }

    fun addText() {
        val clipId = newId("text")
        if (execute(AddTextClip(clipId, TextStyle("Your title"), startUs = currentFrame(), durationUs = DEFAULT_STILL_DURATION_US))) {
            _state.update { it.copy(selectedClipIds = setOf(clipId)) }
        } else {
            showMessage("Unlock the text track to add titles.")
        }
    }

    fun setSpeed(clipId: String, speed: Float) {
        if (!execute(SetClipSpeed(clipId, speed))) showMessage("Speed unchanged.")
    }

    fun toggleTrackMute(trackId: String) {
        val track = history?.current?.sequence?.track(trackId) ?: return
        execute(SetTrackMuted(trackId, !track.muted))
    }

    fun toggleTrackLock(trackId: String) {
        val track = history?.current?.sequence?.track(trackId) ?: return
        execute(SetTrackLocked(trackId, !track.locked))
    }

    fun addAudioTrack() {
        execute(AddTrack(TrackKind.AUDIO, newId("track")))
    }

    fun changeSettings(settings: ProjectSettings) {
        execute(ChangeProjectSettings(settings))
    }

    fun rename(name: String) {
        execute(RenameProject(name))
    }

    fun setSnapping(enabled: Boolean) = _state.update { it.copy(snapping = enabled) }

    fun setPreviewQuality(quality: PreviewQuality) {
        _state.update { it.copy(previewQuality = quality) }
        displayed?.let { preview.setProject(it, quality) }
    }

    // -----------------------------------------------------------------------------------------
    // Playback
    // -----------------------------------------------------------------------------------------

    private fun currentFrame(): Long {
        val project = history?.current ?: return 0
        return project.settings.frameRate.floorToFrame(preview.state.value.positionUs)
    }

    fun seek(timeUs: Long) {
        val project = displayed ?: return
        preview.seekTo(project.settings.frameRate.roundToFrame(timeUs.coerceAtLeast(0)))
    }

    fun stepFrames(frames: Int) {
        val project = displayed ?: return
        preview.pause()
        val rate = project.settings.frameRate
        val index = (rate.frameIndexAt(preview.state.value.positionUs) + frames).coerceAtLeast(0)
        preview.seekTo(rate.frameStartUs(index))
    }

    fun seekRelative(deltaUs: Long) = seek(preview.state.value.positionUs + deltaUs)

    // -----------------------------------------------------------------------------------------
    // Media
    // -----------------------------------------------------------------------------------------

    fun importMedia(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val project = history?.current ?: return
        _state.update { it.copy(importing = true) }
        viewModelScope.launch {
            val timelineWasEmpty = project.sequence.allClips.isEmpty()
            val imported = ArrayList<MediaAsset>()
            val failures = ArrayList<String>()
            for (uri in uris) {
                deps.access.persistReadPermission(uri)
                val existing = history?.current?.assets?.firstOrNull { it.uri == uri.toString() }
                if (existing != null) {
                    imported += existing
                    continue
                }
                when (val r = deps.probe.probe(uri)) {
                    is ProbeResult.Success -> imported += r.asset
                    is ProbeResult.Failure -> failures += "${r.displayName}: ${r.reason}"
                }
            }
            val fresh = imported.filter { a -> history?.current?.asset(a.id) == null }
            val commands = ArrayList<EditCommand>()
            if (fresh.isNotEmpty()) commands += ImportAssets(fresh)
            if (timelineWasEmpty) {
                // First import into an empty project: lay the media out in order, ready to edit.
                imported.forEach { asset -> commands += AddAssetClip(asset.id, newId("clip")) }
            }
            if (commands.isNotEmpty()) {
                execute(CompositeCommand(if (fresh.size == 1) "Import media" else "Import ${fresh.size} files", commands))
            }
            _state.update { s -> s.copy(importing = false, recentAssetIds = s.recentAssetIds + imported.map { it.id }) }
            val summary = buildString {
                if (imported.isNotEmpty()) {
                    append("Imported ${imported.size} file${if (imported.size == 1) "" else "s"}")
                    if (timelineWasEmpty) append(" and added to the timeline")
                    append('.')
                }
                if (failures.isNotEmpty()) {
                    if (isNotEmpty()) append(' ')
                    append("Couldn't import ${failures.size}: ${failures.joinToString("; ")}")
                }
            }
            if (summary.isNotEmpty()) showMessage(summary)
        }
    }

    fun relink(assetId: String, uri: Uri) {
        viewModelScope.launch {
            deps.access.persistReadPermission(uri)
            when (val r = deps.probe.probe(uri)) {
                is ProbeResult.Success -> {
                    val old = history?.current?.asset(assetId) ?: return@launch
                    if (r.asset.type != old.type) {
                        showMessage("Pick a ${old.type.name.lowercase()} file to replace ${old.displayName}.")
                        return@launch
                    }
                    if (old.durationUs > 0 && r.asset.durationUs + 1_000 < old.durationUs) {
                        showMessage("The new file is shorter than the original; trimmed clips may read past its end.")
                    }
                    execute(RelinkAsset(assetId, r.asset.uri, r.asset.displayName))
                    refreshMissingMedia()
                }
                is ProbeResult.Failure -> showMessage("Couldn't use that file: ${r.reason}")
            }
        }
    }

    fun refreshMissingMedia() {
        val project = history?.current ?: return
        viewModelScope.launch {
            val missing = deps.access.findMissing(project.assets)
            _state.update { it.copy(missingAssetIds = missing) }
            if (missing.isNotEmpty()) {
                showMessage("${missing.size} media file${if (missing.size == 1) " is" else "s are"} missing. Relink them from the media panel.")
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Saving
    // -----------------------------------------------------------------------------------------

    private fun scheduleSave() {
        _state.update { it.copy(saveStatus = SaveStatus.UNSAVED) }
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(AUTOSAVE_DELAY_MS)
            save()
        }
    }

    /** Saves immediately (e.g. when the app goes to the background or the editor closes). */
    fun flushSave() {
        commitLive()
        if (_state.value.saveStatus == SaveStatus.SAVED) return
        saveJob?.cancel()
        val project = history?.current ?: return
        deps.appScope.launch { persist(project) }
    }

    private suspend fun save() {
        val project = history?.current ?: return
        persist(project)
    }

    private suspend fun persist(project: Project) {
        _state.update { it.copy(saveStatus = SaveStatus.SAVING) }
        try {
            deps.repository.save(project)
            if (history?.current == project) _state.update { it.copy(saveStatus = SaveStatus.SAVED) }
            updateProjectThumbnail(project)
        } catch (e: Exception) {
            _state.update { it.copy(saveStatus = SaveStatus.FAILED) }
            showMessage("Saving failed: ${e.message ?: "unknown error"}. Your edits are kept in memory; try again.")
        }
    }

    private suspend fun updateProjectThumbnail(project: Project) {
        val first = project.sequence.mainVideoTrack?.clips?.firstOrNull() ?: return
        val asset = project.asset(first.assetId) ?: return
        val key = "${asset.uri}|${first.sourceStartUs}"
        if (key == lastThumbnailKey) return
        val bitmap = deps.thumbnails.frame(asset, first.sourceStartUs, 360) ?: return
        val file = withContext(Dispatchers.IO) {
            val dir = File(deps.context.filesDir, "project-thumbnails").apply { mkdirs() }
            File(dir, "${project.id}.jpg").also { f ->
                FileOutputStream(f).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            }
        }
        deps.repository.setThumbnail(project.id, file.absolutePath)
        lastThumbnailKey = key
    }

    // -----------------------------------------------------------------------------------------

    /** The editor became visible: rebuild the preview (decoders were released on stop). */
    fun onStart() {
        displayed?.let { preview.setProject(it, _state.value.previewQuality) }
        refreshMissingMedia()
    }

    /** The editor left the screen: save, stop playback and free hardware decoders. */
    fun onStop() {
        flushSave()
        preview.pause()
        preview.release()
        viewModelScope.launch { deps.thumbnails.releaseDecoders() }
    }

    fun showMessage(text: String) = _state.update { it.copy(message = text) }

    fun messageShown() = _state.update { it.copy(message = null) }

    override fun onCleared() {
        flushSave()
        preview.destroy()
        super.onCleared()
    }

    private fun newId(prefix: String) = "$prefix-${UUID.randomUUID()}"

    private companion object {
        const val AUTOSAVE_DELAY_MS = 900L
        const val LIVE_PREVIEW_DEBOUNCE_MS = 140L
    }
}
