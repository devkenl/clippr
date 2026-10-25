package app.clippr.feature.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Typeface as ComposeTypeface
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clippr.core.model.MediaAsset
import app.clippr.core.model.MediaType
import app.clippr.core.model.Project
import app.clippr.core.model.time.US_PER_SECOND
import app.clippr.core.render.PreviewState
import app.clippr.core.ui.components.EmptyState
import app.clippr.core.ui.components.SecondaryButton
import app.clippr.core.ui.icons.ClipprIcons
import app.clippr.core.ui.theme.palette
import app.clippr.feature.inspector.FontChoice
import app.clippr.feature.inspector.Inspector
import app.clippr.feature.inspector.InspectorActions
import app.clippr.feature.timeline.ClipMenuItem
import app.clippr.feature.timeline.Timeline
import app.clippr.feature.timeline.TimelineMetrics
import app.clippr.feature.timeline.TimelineViewport
import app.clippr.feature.timeline.rememberTimelineMediaCache
import app.clippr.feature.timeline.rememberTimelineViewport

private val ImportMimeTypes = arrayOf("video/*", "image/*", "audio/*")
private val FontMimeTypes = arrayOf(
    "font/*", "application/x-font-ttf", "application/x-font-otf", "application/font-sfnt",
    "application/vnd.ms-opentype", "application/octet-stream",
)

@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onBack: () -> Unit,
    onExport: (projectId: String) -> Unit,
) {
    val c = palette
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val preview by viewModel.preview.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val focus = remember { FocusRequester() }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var focusMode by rememberSaveable { mutableStateOf(false) }
    var guides by rememberSaveable { mutableStateOf(GuideMode.OFF) }
    var relinkTarget by remember { mutableStateOf<MediaAsset?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.importMedia(uris)
    }
    val relinkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = relinkTarget
        if (uri != null && target != null) viewModel.relink(target.id, uri)
        relinkTarget = null
    }
    var importTargetClipId by remember { mutableStateOf<String?>(null) }
    val fontLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importFont(uri, importTargetClipId)
    }
    val lutLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importLut(uri, importTargetClipId)
    }
    val requestFont = {
        importTargetClipId = ui.selectedClip?.takeIf { it.isText }?.id
        fontLauncher.launch(FontMimeTypes)
    }
    val requestLut = {
        importTargetClipId = ui.selectedClip?.takeIf { !it.isText }?.id
        lutLauncher.launch(arrayOf("*/*"))
    }
    val requestImport = { importLauncher.launch(ImportMimeTypes) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.openVoiceover()
        if (!granted) viewModel.voiceoverPermissionDenied()
    }
    val requestVoiceover: () -> Unit = {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.openVoiceover() else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
    }
    val requestRelink = { asset: MediaAsset ->
        relinkTarget = asset
        relinkLauncher.launch(
            arrayOf(
                when (asset.type) {
                    MediaType.VIDEO -> "video/*"
                    MediaType.IMAGE -> "image/*"
                    MediaType.AUDIO -> "audio/*"
                },
            ),
        )
    }

    LifecycleStartEffect(viewModel) {
        viewModel.onStart()
        onStopOrDispose { viewModel.onStop() }
    }
    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }
    // Keep the screen awake while playing.
    val view = LocalView.current
    LaunchedEffect(preview.playRequested, ui.voiceover.recording) { view.keepScreenOn = preview.playRequested || ui.voiceover.recording }

    val leave = {
        viewModel.flushSave()
        onBack()
    }
    BackHandler(enabled = fullscreen) { fullscreen = false }
    BackHandler(enabled = !fullscreen) { leave() }

    val project = ui.project
    Box(Modifier.fillMaxSize().background(c.background).systemBarsPadding()) {
        when {
            ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.accent) }
            ui.loadError != null || project == null -> EmptyState(
                icon = ClipprIcons.Warning,
                title = "Can't open project",
                body = ui.loadError ?: "Unknown error",
                modifier = Modifier.fillMaxSize(),
                action = { SecondaryButton("Back to projects", onBack) },
            )
            else -> {
                val viewport = rememberTimelineViewport()
                val media = rememberTimelineMediaCache(viewModel.thumbnails, viewModel.waveforms)
                val actions = rememberEditorActions(viewModel, project, ui, preview, viewport, onExport, requestImport, requestFont, requestLut, requestVoiceover)
                BoxWithConstraints(
                    Modifier
                        .fillMaxSize()
                        .focusRequester(focus)
                        .focusable()
                        .onKeyEvent { handleShortcut(it, actions, viewModel, project, ui) { fullscreen = !fullscreen } },
                ) {
                    val wide = maxWidth >= 840.dp || (maxWidth >= 600.dp && maxWidth > maxHeight)
                    when {
                        fullscreen -> FullscreenPreview(viewModel, project, ui, preview, guides) { fullscreen = false }
                        wide -> TabletEditor(viewModel, project, ui, preview, viewport, media, actions, guides, { guides = guides.next() }, focusMode, { focusMode = !focusMode }, { fullscreen = true }, leave, requestImport, requestRelink)
                        else -> PhoneEditor(viewModel, project, ui, preview, viewport, media, actions, guides, { guides = guides.next() }, { fullscreen = true }, leave, requestImport, requestRelink)
                    }
                }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

                // Overlays: cloud media warning, preview errors and the voiceover recorder.
                Column(
                    Modifier.align(Alignment.TopCenter).padding(top = 64.dp, start = 12.dp, end = 12.dp).widthIn(max = 640.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (ui.cloudAssetIds.isNotEmpty() && !fullscreen) {
                        CloudMediaBanner(ui.cloudAssetIds.size, ui.importing, ui.importStatus, viewModel::copyCloudMediaToDevice)
                    } else {
                        ui.importStatus?.let { ImportStatusChip(it) }
                    }
                    preview.error?.let { message ->
                        PreviewErrorCard(
                            message,
                            onRetry = viewModel.preview::retry,
                            onDismiss = viewModel.preview::clearError,
                            onRestart = if (preview.suggestRestart) viewModel::restartApp else null,
                        )
                    }
                    if (preview.transitionsSimplified && preview.error == null && !fullscreen) {
                        PreviewNoticeChip("Transitions preview as cuts on this device. Your export includes them.")
                    }
                }
                if (ui.voiceover.visible) {
                    VoiceoverCard(
                        state = ui.voiceover,
                        timecode = app.clippr.core.model.time.Timecode.format(ui.voiceover.startUs.takeIf { ui.voiceover.recording || ui.voiceover.countdown > 0 } ?: preview.positionUs, project.settings.frameRate),
                        onRecord = viewModel::startVoiceover,
                        onStop = viewModel::stopVoiceover,
                        onClose = viewModel::closeVoiceover,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp, start = 12.dp, end = 12.dp),
                    )
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
    }
}

/** Shared editor operations, bound to the current state. */
internal class EditorActions(
    val split: () -> Unit,
    val delete: (ripple: Boolean) -> Unit,
    val addText: () -> Unit,
    val export: () -> Unit,
    val importMedia: () -> Unit,
    val zoom: (Float) -> Unit,
    val fit: () -> Unit,
    val duplicate: () -> Unit,
    val freeze: () -> Unit,
    val inspector: InspectorActions,
    val fonts: List<FontChoice>,
    val clipMenu: (String) -> List<ClipMenuItem>,
    val voiceover: () -> Unit,
)

@Composable
private fun rememberEditorActions(
    vm: EditorViewModel,
    project: Project,
    ui: EditorUiState,
    preview: PreviewState,
    viewport: TimelineViewport,
    onExport: (String) -> Unit,
    requestImport: () -> Unit,
    requestFont: () -> Unit,
    requestLut: () -> Unit,
    requestVoiceover: () -> Unit,
): EditorActions {
    val inspector = InspectorActions(
        onLive = vm::liveUpdate,
        onCommit = vm::commitLive,
        onSpeed = vm::setSpeed,
        onProjectSettings = vm::changeSettings,
        onRenameProject = vm::rename,
        onSeek = vm::seek,
        onImportFont = requestFont,
        onImportLut = requestLut,
        onCommand = { vm.execute(it) },
    )
    val fonts = remember(ui.fonts) {
        ui.fonts.map { f ->
            FontChoice(f.id, f.name, FontFamily(ComposeTypeface(vm.resources.fonts.typeface(f.id, false))), f.isCustom)
        }
    }
    val clipMenu: (String) -> List<ClipMenuItem> = { clipId ->
        val clip = project.sequence.clip(clipId)
        val isVideo = project.asset(clip?.assetId)?.type == MediaType.VIDEO
        val underPlayhead = clip?.contains(preview.positionUs) == true
        buildList {
            add(ClipMenuItem("Split at playhead", ClipprIcons.Split, enabled = underPlayhead) { vm.splitClip(clipId) })
            add(ClipMenuItem("Duplicate", ClipprIcons.Copy) { vm.duplicateClip(clipId) })
            val track = project.sequence.trackOfClip(clipId)
            val index = track?.clips?.indexOfFirst { it.id == clipId } ?: -1
            if (track?.allowsOverlap == true && index > 0) {
                val hasTransition = app.clippr.core.model.edit.Overlaps.overlapIn(track.clips, index) > 0
                add(ClipMenuItem(if (hasTransition) "Edit transition" else "Add transition", ClipprIcons.Transition) { vm.openTransition(clipId) })
            }
            if (isVideo) add(ClipMenuItem("Freeze frame", ClipprIcons.Freeze, enabled = underPlayhead) { vm.freezeFrame(clipId) })
            add(ClipMenuItem("Delete", ClipprIcons.Trash, destructive = true) { vm.deleteClip(clipId, ripple = false) })
            add(ClipMenuItem("Ripple delete", ClipprIcons.Trash, destructive = true) { vm.deleteClip(clipId, ripple = true) })
        }
    }
    val maxUs = project.durationUs
    return EditorActions(
        split = vm::splitAtPlayhead,
        delete = { ripple -> vm.deleteSelection(ripple) },
        addText = vm::addText,
        export = {
            vm.flushSave()
            vm.preview.pause()
            onExport(project.id)
        },
        importMedia = requestImport,
        zoom = { f -> viewport.zoomBy(f, viewport.timeToX(preview.positionUs).coerceIn(0f, viewport.widthPx), maxUs) },
        fit = { viewport.fit(maxUs) },
        duplicate = { ui.selectedClipIds.singleOrNull()?.let(vm::duplicateClip) ?: vm.showMessage("Select one clip to duplicate.") },
        freeze = { vm.freezeFrame(ui.selectedClip?.id) },
        inspector = inspector,
        fonts = fonts,
        clipMenu = clipMenu,
        voiceover = requestVoiceover,
    )
}

private fun handleShortcut(
    event: androidx.compose.ui.input.key.KeyEvent,
    actions: EditorActions,
    vm: EditorViewModel,
    project: Project,
    ui: EditorUiState,
    toggleFullscreen: () -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val ctrl = event.isCtrlPressed || event.isMetaPressed
    val shift = event.isShiftPressed
    when (event.key) {
        Key.Spacebar, Key.K -> if (ui.voiceover.recording) vm.stopVoiceover() else vm.preview.togglePlayPause()
        Key.S -> if (!ctrl) actions.split() else return false
        Key.B -> if (ctrl) actions.split() else return false
        Key.Delete, Key.Backspace -> actions.delete(shift)
        Key.Z -> when {
            ctrl && shift -> vm.redo()
            ctrl -> vm.undo()
            else -> return false
        }
        Key.Y -> if (ctrl) vm.redo() else return false
        Key.DirectionLeft -> if (shift) vm.seekRelative(-US_PER_SECOND) else vm.stepFrames(-1)
        Key.DirectionRight -> if (shift) vm.seekRelative(US_PER_SECOND) else vm.stepFrames(1)
        Key.J -> vm.seekRelative(-US_PER_SECOND)
        Key.L -> vm.seekRelative(US_PER_SECOND)
        Key.MoveHome -> vm.seek(0)
        Key.MoveEnd -> vm.seek(project.durationUs)
        Key.Equals, Key.Plus, Key.NumPadAdd -> actions.zoom(1.25f)
        Key.Minus, Key.NumPadSubtract -> actions.zoom(0.8f)
        Key.Backslash -> actions.fit()
        Key.I -> if (ctrl) actions.importMedia() else return false
        Key.T -> actions.addText()
        Key.N -> vm.setSnapping(!ui.snapping)
        Key.F -> toggleFullscreen()
        Key.E -> if (ctrl) actions.export() else return false
        Key.D -> if (ctrl) actions.duplicate() else return false
        Key.Escape -> if (ui.voiceover.visible) vm.closeVoiceover() else vm.select(null)
        Key.R -> if (!ctrl) actions.voiceover() else return false
        else -> return false
    }
    return true
}

@Composable
private fun TabletEditor(
    vm: EditorViewModel,
    project: Project,
    ui: EditorUiState,
    preview: PreviewState,
    viewport: TimelineViewport,
    media: app.clippr.feature.timeline.TimelineMediaCache,
    actions: EditorActions,
    guides: GuideMode,
    onGuides: () -> Unit,
    focusMode: Boolean,
    onToggleFocus: () -> Unit,
    onFullscreen: () -> Unit,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onRelink: (MediaAsset) -> Unit,
) {
    val c = palette
    val density = LocalDensity.current
    var timelineFraction by rememberSaveable { mutableFloatStateOf(0.40f) }

    Column(Modifier.fillMaxSize()) {
        EditorTopBar(
            name = project.name,
            saveStatus = ui.saveStatus,
            canUndo = ui.canUndo,
            canRedo = ui.canRedo,
            undoLabel = ui.undoLabel,
            redoLabel = ui.redoLabel,
            focusMode = focusMode,
            compact = false,
            onBack = onBack,
            onUndo = vm::undo,
            onRedo = vm::redo,
            onToggleFocus = onToggleFocus,
            onExport = actions.export,
            exportEnabled = project.durationUs > 0,
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val totalHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f - timelineFraction).fillMaxWidth()) {
                    if (!focusMode) {
                        MediaPanel(
                            project = project,
                            missingAssetIds = ui.missingAssetIds,
                            recentAssetIds = ui.recentAssetIds,
                            importing = ui.importing,
                            thumbnails = vm.thumbnails,
                            onImport = onImport,
                            onAdd = vm::addToTimeline,
                            onRelink = onRelink,
                            onAddText = actions.addText,
                            modifier = Modifier.width(296.dp).fillMaxHeight(),
                            onAddOverlay = vm::addAsOverlay,
                        )
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        PreviewMonitor(
                            project = project,
                            engine = vm.preview,
                            playheadUs = preview.positionUs,
                            selectedClip = ui.selectedClip,
                            lockedTrackIds = project.sequence.tracks.filter { it.locked }.map { it.id }.toSet(),
                            guides = guides,
                            onLiveTransform = vm::liveTransform,
                            fonts = vm.resources.fonts,
                            onCommit = vm::commitLive,
                            onSelect = { vm.select(it) },
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                        TransportBar(
                            isPlaying = preview.playRequested,
            buffering = preview.isBuffering,
                            positionUs = preview.positionUs,
                            durationUs = project.durationUs,
                            frameRate = project.settings.frameRate,
                            quality = ui.previewQuality,
                            guides = guides,
                            compact = false,
                            enabled = !preview.isEmpty,
                            onPlayPause = vm.preview::togglePlayPause,
                            onStep = vm::stepFrames,
                            onStart = { vm.seek(0) },
                            onEnd = { vm.seek(project.durationUs) },
                            onQuality = vm::setPreviewQuality,
                            onGuides = onGuides,
                            onFullscreen = onFullscreen,
                        )
                    }
                    if (!focusMode) {
                        Box(Modifier.width(1.dp).fillMaxHeight().background(c.hairline))
                        Inspector(
                            project = project,
                            selectedClip = ui.selectedClip,
                            actions = actions.inspector,
                            playheadUs = preview.positionUs,
                            fonts = actions.fonts,
                            luts = ui.luts,
                            modifier = Modifier.width(336.dp).fillMaxHeight().background(c.surface),
                            tabRequest = ui.inspectorRequest,
                        )
                    }
                }
                ResizeHandle(onDrag = { dy ->
                    timelineFraction = (timelineFraction - dy / totalHeightPx).coerceIn(0.22f, 0.72f)
                })
                TimelineToolbar(
                    hasSelection = ui.selectedClipIds.isNotEmpty(),
                    snapping = ui.snapping,
                    compact = false,
                    onSplit = actions.split,
                    onDelete = { actions.delete(false) },
                    onAddText = actions.addText,
                    onDuplicate = actions.duplicate,
                    onFreeze = actions.freeze,
                    onSnapping = { vm.setSnapping(!ui.snapping) },
                    onZoomIn = { actions.zoom(1.25f) },
                    onZoomOut = { actions.zoom(0.8f) },
                    onFit = actions.fit,
                    onVoiceover = actions.voiceover,
                )
                EditorTimeline(vm, project, ui, preview, viewport, media, actions, TimelineMetrics.Tablet, Modifier.weight(timelineFraction).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun EditorTimeline(
    vm: EditorViewModel,
    project: Project,
    ui: EditorUiState,
    preview: PreviewState,
    viewport: TimelineViewport,
    media: app.clippr.feature.timeline.TimelineMediaCache,
    actions: EditorActions,
    metrics: TimelineMetrics,
    modifier: Modifier,
) {
    Timeline(
        project = project,
        playheadUs = preview.positionUs,
        isPlaying = preview.isPlaying,
        selectedClipIds = ui.selectedClipIds,
        missingAssetIds = ui.missingAssetIds,
        snappingEnabled = ui.snapping,
        viewport = viewport,
        media = media,
        metrics = metrics,
        onSeek = vm::seek,
        onScrubbing = vm.preview::setScrubbing,
        onSelect = vm::select,
        onEdit = { vm.execute(it) },
        onToggleTrackMute = vm::toggleTrackMute,
        onToggleTrackLock = vm::toggleTrackLock,
        onAddTrack = vm::addTrack,
        modifier = modifier,
        clipMenu = actions.clipMenu,
        onToggleTrackHidden = vm::toggleTrackHidden,
        onTransitionTap = vm::openTransition,
    )
}

private enum class PhoneSheet { MEDIA, INSPECTOR, PROJECT }

@Composable
private fun PhoneEditor(
    vm: EditorViewModel,
    project: Project,
    ui: EditorUiState,
    preview: PreviewState,
    viewport: TimelineViewport,
    media: app.clippr.feature.timeline.TimelineMediaCache,
    actions: EditorActions,
    guides: GuideMode,
    onGuides: () -> Unit,
    onFullscreen: () -> Unit,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onRelink: (MediaAsset) -> Unit,
) {
    val c = palette
    var sheet by rememberSaveable { mutableStateOf<PhoneSheet?>(null) }
    // Tapping a transition on the timeline opens the inspector at its Transition tab.
    LaunchedEffect(ui.inspectorRequest?.nonce) { if (ui.inspectorRequest != null) sheet = PhoneSheet.INSPECTOR }
    Column(Modifier.fillMaxSize()) {
        EditorTopBar(
            name = project.name,
            saveStatus = ui.saveStatus,
            canUndo = ui.canUndo,
            canRedo = ui.canRedo,
            undoLabel = ui.undoLabel,
            redoLabel = ui.redoLabel,
            focusMode = false,
            compact = true,
            onBack = onBack,
            onUndo = vm::undo,
            onRedo = vm::redo,
            onToggleFocus = null,
            onExport = actions.export,
            exportEnabled = project.durationUs > 0,
        )
        PreviewMonitor(
            project = project,
            engine = vm.preview,
            playheadUs = preview.positionUs,
            selectedClip = ui.selectedClip,
            lockedTrackIds = project.sequence.tracks.filter { it.locked }.map { it.id }.toSet(),
            guides = guides,
            onLiveTransform = vm::liveTransform,
            fonts = vm.resources.fonts,
            onCommit = vm::commitLive,
            onSelect = { vm.select(it) },
            modifier = Modifier.fillMaxWidth().weight(0.42f),
        )
        TransportBar(
            isPlaying = preview.playRequested,
            buffering = preview.isBuffering,
            positionUs = preview.positionUs,
            durationUs = project.durationUs,
            frameRate = project.settings.frameRate,
            quality = ui.previewQuality,
            guides = guides,
            compact = true,
            enabled = !preview.isEmpty,
            onPlayPause = vm.preview::togglePlayPause,
            onStep = vm::stepFrames,
            onStart = { vm.seek(0) },
            onEnd = { vm.seek(project.durationUs) },
            onQuality = vm::setPreviewQuality,
            onGuides = onGuides,
            onFullscreen = onFullscreen,
        )
        TimelineToolbar(
            hasSelection = ui.selectedClipIds.isNotEmpty(),
            snapping = ui.snapping,
            compact = true,
            onSplit = actions.split,
            onDelete = { actions.delete(false) },
            onAddText = actions.addText,
            onDuplicate = actions.duplicate,
            onFreeze = actions.freeze,
            onSnapping = { vm.setSnapping(!ui.snapping) },
            onZoomIn = { actions.zoom(1.25f) },
            onZoomOut = { actions.zoom(0.8f) },
            onFit = actions.fit,
            onVoiceover = actions.voiceover,
        )
        EditorTimeline(vm, project, ui, preview, viewport, media, actions, TimelineMetrics.Phone, Modifier.weight(0.58f).fillMaxWidth())
        ToolDrawer(
            hasSelection = ui.selectedClipIds.isNotEmpty(),
            onMedia = { sheet = PhoneSheet.MEDIA },
            onSplit = actions.split,
            onDelete = { actions.delete(false) },
            onText = actions.addText,
            onDuplicate = actions.duplicate,
            onFreeze = actions.freeze,
            onEdit = { sheet = PhoneSheet.INSPECTOR },
            onProject = { sheet = PhoneSheet.PROJECT },
            onVoiceover = actions.voiceover,
        )
    }

    val current = sheet
    if (current != null) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = current == PhoneSheet.MEDIA)
        ModalBottomSheet(
            onDismissRequest = {
                vm.commitLive()
                sheet = null
            },
            sheetState = state,
            containerColor = c.surface,
        ) {
            when (current) {
                PhoneSheet.MEDIA -> MediaPanel(
                    project = project,
                    missingAssetIds = ui.missingAssetIds,
                    recentAssetIds = ui.recentAssetIds,
                    importing = ui.importing,
                    thumbnails = vm.thumbnails,
                    onImport = onImport,
                    onAdd = {
                        vm.addToTimeline(it)
                        sheet = null
                    },
                    onRelink = onRelink,
                    onAddText = {
                        actions.addText()
                        sheet = null
                    },
                    modifier = Modifier.fillMaxWidth().height(520.dp),
                    onAddOverlay = {
                        vm.addAsOverlay(it)
                        sheet = null
                    },
                )
                PhoneSheet.INSPECTOR -> {
                    val clip = ui.selectedClip
                    if (clip == null) {
                        Text("Select a clip on the timeline first.", color = c.textSecondary, modifier = Modifier.padding(24.dp))
                    } else {
                        Inspector(project, clip, actions.inspector, Modifier.fillMaxWidth().height(520.dp), preview.positionUs, actions.fonts, ui.luts, ui.inspectorRequest)
                    }
                }
                PhoneSheet.PROJECT -> Inspector(project, null, actions.inspector, Modifier.fillMaxWidth().height(520.dp))
            }
        }
    }
}

@Composable
private fun FullscreenPreview(
    vm: EditorViewModel,
    project: Project,
    ui: EditorUiState,
    preview: PreviewState,
    guides: GuideMode,
    onExit: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
        PreviewMonitor(
            project = project,
            engine = vm.preview,
            playheadUs = preview.positionUs,
            selectedClip = null,
            lockedTrackIds = emptySet(),
            guides = guides,
            onLiveTransform = { _, _ -> },
            fonts = vm.resources.fonts,
            interactive = false,
            onCommit = {},
            onSelect = {},
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        TransportBar(
            isPlaying = preview.playRequested,
            buffering = preview.isBuffering,
            positionUs = preview.positionUs,
            durationUs = project.durationUs,
            frameRate = project.settings.frameRate,
            quality = ui.previewQuality,
            guides = guides,
            compact = false,
            enabled = !preview.isEmpty,
            onPlayPause = vm.preview::togglePlayPause,
            onStep = vm::stepFrames,
            onStart = { vm.seek(0) },
            onEnd = { vm.seek(project.durationUs) },
            onQuality = vm::setPreviewQuality,
            onGuides = {},
            onFullscreen = onExit,
        )
    }
}
