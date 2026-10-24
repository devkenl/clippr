package app.clippr.feature.home

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clippr.core.database.ProjectSummary
import app.clippr.core.database.SettingsValues
import app.clippr.core.model.AspectRatioPreset
import app.clippr.core.model.ProjectSettings
import app.clippr.core.model.ProjectTemplate
import app.clippr.core.model.time.FrameRate
import app.clippr.core.ui.components.ClipprLockup
import app.clippr.core.ui.components.PillTabs
import app.clippr.core.ui.components.PrimaryButton
import app.clippr.core.ui.components.SegmentedChoice
import app.clippr.core.ui.components.ToolButton
import app.clippr.core.ui.icons.ClipprIcons
import app.clippr.core.ui.theme.Spacing
import app.clippr.core.ui.theme.palette

/** Home's top-level pages, shown as tabs across the top. */
enum class HomePage(val label: String) {
    HOME("Home"),
    PROJECTS("Projects"),
    MEDIA("Media"),
    TEMPLATES("Templates"),
    LEARN("Learn"),
    SETTINGS("Settings"),
}

private val ImportMimeTypes = arrayOf("video/*", "image/*", "audio/*")

@Composable
fun HomeScreen(viewModel: HomeViewModel, versionName: String, onOpenProject: (String) -> Unit) {
    val c = palette
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var pageName by rememberSaveable { mutableStateOf(HomePage.HOME.name) }
    val page = HomePage.valueOf(pageName)
    var query by rememberSaveable { mutableStateOf("") }
    var creating by rememberSaveable { mutableStateOf(false) }
    var showExports by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ProjectSummary?>(null) }
    var deleting by remember { mutableStateOf<ProjectSummary?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        viewModel.importToNewProject(uris, onOpenProject)
    }
    val useTemplate: (ProjectTemplate) -> Unit = { t -> viewModel.createFromTemplate(t, onOpenProject) }

    BackHandler(enabled = page != HomePage.HOME || query.isNotEmpty()) {
        if (query.isNotEmpty()) query = "" else pageName = HomePage.HOME.name
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }
    // Storage and export availability can change while Clippr is in the background.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshStorage()
        onPauseOrDispose { }
    }

    val actions = HomeActions(
        newProject = { creating = true },
        importMedia = { importLauncher.launch(ImportMimeTypes) },
        templates = { pageName = HomePage.TEMPLATES.name },
        allProjects = { pageName = HomePage.PROJECTS.name },
        manageStorage = { pageName = HomePage.SETTINGS.name },
        openProject = onOpenProject,
        rename = { renaming = it },
        duplicate = viewModel::duplicate,
        delete = { deleting = it },
        playExport = { e -> runCatching { context.startActivity(viewModel.videoLibrary.viewIntent(Uri.parse(e.uri))) } },
        shareExport = { e -> runCatching { context.startActivity(viewModel.videoLibrary.shareIntent(Uri.parse(e.uri), "Share ${e.projectName}")) } },
        removeExport = { e -> viewModel.removeExport(e.id) },
        allExports = { showExports = true },
    )

    BoxWithConstraints(Modifier.fillMaxSize().background(c.background).systemBarsPadding()) {
        val wide = maxWidth >= 900.dp
        val medium = maxWidth >= 600.dp
        val gutter = if (medium) 32.dp else 16.dp
        val columns = when {
            maxWidth >= 1200.dp -> 3
            medium -> if (wide) 3 else 2
            else -> 2
        }
        Column(Modifier.fillMaxSize()) {
            TopNavigation(page, { pageName = it.name; query = "" }, query, { query = it }, wide = medium, gutter = gutter)
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = gutter)
                    .padding(top = Spacing.md, bottom = 40.dp),
            ) {
                if (query.isNotBlank() && page != HomePage.PROJECTS && page != HomePage.MEDIA && page != HomePage.TEMPLATES) {
                    SearchResults(query, state.projects, columns, actions, useTemplate)
                } else {
                    when (page) {
                        HomePage.HOME -> HomeTab(state, wide, actions, versionName)
                        HomePage.PROJECTS -> ProjectsTab(state, columns, query, actions)
                        HomePage.MEDIA -> MediaTab(state, columns, query, viewModel.thumbnails, viewModel::loadLibrary, actions.importMedia, onOpenProject)
                        HomePage.TEMPLATES -> TemplatesTab(columns, query, busy = state.busy != null, onUse = useTemplate)
                        HomePage.LEARN -> LearnTab(wide)
                        HomePage.SETTINGS -> SettingsTab(
                            state,
                            wide,
                            versionName,
                            SettingsActions(
                                update = viewModel::updateSettings,
                                clearCache = viewModel::clearCache,
                                findUnused = viewModel::findUnusedMedia,
                                removeUnused = viewModel::removeUnusedMedia,
                                refreshStorage = viewModel::refreshStorage,
                            ),
                        )
                    }
                }
            }
        }
        state.busy?.let { BusyOverlay(it) }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
    }

    if (creating) {
        NewProjectSheet(
            defaults = state.settings,
            onDismiss = { creating = false },
            onCreate = { name, settings ->
                creating = false
                viewModel.create(name, settings, onOpenProject)
            },
        )
    }
    if (showExports) {
        ModalBottomSheet(onDismissRequest = { showExports = false }, containerColor = c.background) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl).verticalScroll(rememberScrollState())) {
                Text("All exports", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
                Spacer(Modifier.height(Spacing.md))
                RecentExportsCard(state, actions, limit = Int.MAX_VALUE, showHeader = false)
            }
        }
    }
    renaming?.let { p ->
        RenameDialog(p.name, onDismiss = { renaming = null }) { name ->
            viewModel.rename(p.id, name)
            renaming = null
        }
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${p.name}”?") },
            text = { Text("The project and its edits are removed from Clippr. Your original media files and exported videos are not touched.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(p.id)
                    deleting = null
                }) { Text("Delete", color = c.danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
            containerColor = c.panel,
        )
    }
}

@Composable
private fun TopNavigation(
    page: HomePage,
    onPage: (HomePage) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    wide: Boolean,
    gutter: androidx.compose.ui.unit.Dp,
) {
    val c = palette
    val icons = listOf(ClipprIcons.Home, ClipprIcons.Folder, ClipprIcons.Photo, ClipprIcons.Grid, ClipprIcons.Learn, ClipprIcons.Settings)
    if (wide) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Brand()
            Spacer(Modifier.width(Spacing.xl))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                PillTabs(HomePage.entries.map { it.label }, page.ordinal, { onPage(HomePage.entries[it]) }, icons = icons)
            }
            Spacer(Modifier.width(Spacing.lg))
            SearchField(query, onQuery, Modifier.width(280.dp))
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(horizontal = gutter).padding(top = Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Brand()
                Spacer(Modifier.width(Spacing.md))
                SearchField(query, onQuery, Modifier.weight(1f))
            }
            Spacer(Modifier.height(Spacing.md))
            PillTabs(HomePage.entries.map { it.label }, page.ordinal, { onPage(HomePage.entries[it]) }, compact = true)
        }
    }
}

@Composable
private fun Brand() {
    ClipprLockup(height = 26.dp)
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier) {
    val c = palette
    Row(
        modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(50))
            .background(c.surface)
            .border(1.dp, c.hairline, RoundedCornerShape(50))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ClipprIcons.Search, contentDescription = null, tint = c.textTertiary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Search projects and templates", style = MaterialTheme.typography.bodyMedium, color = c.textTertiary, maxLines = 1)
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.textPrimary),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) ToolButton(ClipprIcons.Close, "Clear search", { onQuery("") }, size = 32.dp)
    }
}

@Composable
private fun BusyOverlay(text: String) {
    val c = palette
    Box(
        Modifier
            .fillMaxSize()
            .background(c.background.copy(alpha = 0.7f))
            .clickable(enabled = true, role = Role.Image, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 360.dp).clip(RoundedCornerShape(18.dp)).background(c.surface).padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(color = c.accent)
            Spacer(Modifier.height(Spacing.lg))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
        }
    }
}

private val Resolutions = listOf(720, 1080, 2160)

@Composable
private fun NewProjectSheet(
    defaults: SettingsValues,
    onDismiss: () -> Unit,
    onCreate: (String, ProjectSettings) -> Unit,
) {
    val c = palette
    var name by rememberSaveable { mutableStateOf("") }
    var preset by rememberSaveable { mutableStateOf(defaults.defaultAspect) }
    var side by rememberSaveable { mutableStateOf(if (defaults.defaultShortSide in Resolutions) defaults.defaultShortSide else 1080) }
    var fpsIndex by rememberSaveable { mutableStateOf(FrameRate.COMMON.indexOf(defaults.defaultFrameRate).coerceAtLeast(0)) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.xl).padding(bottom = Spacing.xl).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text("New project", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
                placeholder = { Text("Untitled project") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.outline, cursorColor = c.accent),
            )
            Text("Format", style = MaterialTheme.typography.titleSmall, color = c.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                AspectRatioPreset.entries.forEach { p ->
                    AspectTile(p, p == preset, Modifier.weight(1f)) { preset = p }
                }
            }
            Text("Resolution", style = MaterialTheme.typography.titleSmall, color = c.textSecondary)
            SegmentedChoice(Resolutions, side, { if (it == 2160) "4K" else "${it}p" }, { side = it }, Modifier.fillMaxWidth())
            Text("Frame rate", style = MaterialTheme.typography.titleSmall, color = c.textSecondary)
            SegmentedChoice(
                FrameRate.COMMON.indices.toList(),
                fpsIndex,
                { "${FrameRate.COMMON[it].numerator} fps" },
                { fpsIndex = it },
                Modifier.fillMaxWidth(),
            )
            val (w, h) = preset.dimensionsForShortSide(side)
            Text("$w × $h", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
            Spacer(Modifier.height(Spacing.xs))
            PrimaryButton(
                "Create project",
                onClick = { onCreate(name, ProjectSettings.create(preset, side, FrameRate.COMMON[fpsIndex])) },
                icon = ClipprIcons.Check,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AspectTile(preset: AspectRatioPreset, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = palette
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) c.accentSoft else c.panel)
            .border(1.dp, if (selected) c.accent else c.hairline, RoundedCornerShape(12.dp))
            .clickable(role = Role.RadioButton, onClickLabel = preset.label, onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val aspect = preset.width.toFloat() / preset.height
        val box = 30.dp
        val (w, h) = if (aspect >= 1f) box to box / aspect else box * aspect to box
        Box(Modifier.height(box), contentAlignment = Alignment.Center) {
            Box(Modifier.size(w, h).border(1.5.dp, if (selected) c.accent else c.textTertiary, RoundedCornerShape(3.dp)))
        }
        Spacer(Modifier.height(6.dp))
        Text(preset.label, style = MaterialTheme.typography.labelSmall, color = if (selected) c.accent else c.textSecondary)
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val c = palette
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename project") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, cursorColor = c.accent),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = c.panel,
    )
}
