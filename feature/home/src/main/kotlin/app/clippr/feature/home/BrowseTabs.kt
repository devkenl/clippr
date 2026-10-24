package app.clippr.feature.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.clippr.core.database.ProjectSummary
import app.clippr.core.media.ThumbnailRepository
import app.clippr.core.model.AspectRatioPreset
import app.clippr.core.model.MediaType
import app.clippr.core.model.ProjectTemplate
import app.clippr.core.model.ProjectTemplates
import app.clippr.core.model.TemplateCategory
import app.clippr.core.model.time.Timecode
import app.clippr.core.render.RenderResources
import app.clippr.core.ui.components.EmptyState
import app.clippr.core.ui.components.FilterChip
import app.clippr.core.ui.components.PrimaryButton
import app.clippr.core.ui.icons.ClipprIcons
import app.clippr.core.ui.theme.Spacing
import app.clippr.core.ui.theme.palette
import androidx.compose.ui.text.font.Typeface as ComposeTypeface

// Projects ---------------------------------------------------------------------------------------

private enum class ProjectSort(val label: String) { RECENT("Recent"), NAME("Name"), LONGEST("Longest") }

@Composable
internal fun ProjectsTab(state: HomeUiState, columns: Int, query: String, actions: HomeActions) {
    val c = palette
    var sortName by rememberSaveable { mutableStateOf(ProjectSort.RECENT.name) }
    var formatName by rememberSaveable { mutableStateOf<String?>(null) }
    val sort = ProjectSort.valueOf(sortName)
    val format = AspectRatioPreset.entries.firstOrNull { it.name == formatName }
    val shown = state.projects
        .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
        .filter { p -> format == null || format.width.toLong() * p.height == format.height.toLong() * p.width }
        .let { list ->
            when (sort) {
                ProjectSort.RECENT -> list.sortedByDescending { it.modifiedAtMs }
                ProjectSort.NAME -> list.sortedBy { it.name.lowercase() }
                ProjectSort.LONGEST -> list.sortedByDescending { it.durationUs }
            }
        }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Projects", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
                Text("${state.projects.size} on this device", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
            }
            PrimaryButton("New project", actions.newProject, icon = ClipprIcons.Plus)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            Icon(ClipprIcons.Sort, contentDescription = "Sort by", tint = c.textTertiary, modifier = Modifier.size(18.dp))
            ProjectSort.entries.forEach { s -> FilterChip(s.label, sort == s, { sortName = s.name }) }
            Spacer(Modifier.width(Spacing.md))
            FilterChip("All formats", format == null, { formatName = null })
            AspectRatioPreset.entries.forEach { f -> FilterChip(f.label, format == f, { formatName = f.name }) }
        }
        if (shown.isEmpty()) {
            EmptyState(
                icon = ClipprIcons.Search,
                title = if (state.projects.isEmpty()) "No projects yet" else "No matching projects",
                body = if (state.projects.isEmpty()) "Create your first project to get started." else "Try another name or format.",
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            GridRows(shown, columns) { p, m ->
                ProjectCard(p, { actions.openProject(p.id) }, { actions.rename(p) }, { actions.duplicate(p.id) }, { actions.delete(p) }, m)
            }
        }
    }
}

// Media ------------------------------------------------------------------------------------------

private enum class MediaFilter(val label: String, val type: MediaType?) {
    ALL("All", null), VIDEOS("Videos", MediaType.VIDEO), PHOTOS("Photos", MediaType.IMAGE), AUDIO("Audio", MediaType.AUDIO)
}

@Composable
internal fun MediaTab(
    state: HomeUiState,
    columns: Int,
    query: String,
    thumbnails: ThumbnailRepository,
    onLoad: () -> Unit,
    onImport: () -> Unit,
    onOpenProject: (String) -> Unit,
) {
    val c = palette
    LaunchedEffect(Unit) { onLoad() }
    var filterName by rememberSaveable { mutableStateOf(MediaFilter.ALL.name) }
    val filter = MediaFilter.valueOf(filterName)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Media", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
                Text("Every file used in your projects. Tap one to open the project that uses it.", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
            }
            PrimaryButton("Import", onImport, icon = ClipprIcons.Import)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            MediaFilter.entries.forEach { f -> FilterChip(f.label, filter == f, { filterName = f.name }) }
        }
        val library = state.library
        when {
            library == null -> Box(Modifier.fillMaxWidth().padding(Spacing.xl), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = c.accent)
            }
            else -> {
                val shown = library
                    .filter { filter.type == null || it.asset.type == filter.type }
                    .filter { query.isBlank() || it.asset.displayName.contains(query.trim(), ignoreCase = true) }
                if (shown.isEmpty()) {
                    EmptyState(ClipprIcons.Photo, "No media here yet", "Import videos, photos or music to start a project.", Modifier.fillMaxWidth())
                } else {
                    GridRows(shown, columns + 1, spacing = Spacing.md) { item, m -> MediaTile(item, thumbnails, onOpenProject, m) }
                }
            }
        }
    }
}

@Composable
private fun MediaTile(item: LibraryItem, thumbnails: ThumbnailRepository, onOpenProject: (String) -> Unit, modifier: Modifier) {
    val c = palette
    val asset = item.asset
    val thumb by produceState<ImageBitmap?>(null, asset.uri) {
        value = if (asset.type == MediaType.AUDIO) null else thumbnails.frame(asset, 0, 240)?.asImageBitmap()
    }
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = "Open ${item.projects.first().second}") { onOpenProject(item.projects.first().first) },
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.3f).clip(RoundedCornerShape(12.dp)).background(c.panel).border(1.dp, c.hairline, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val img = thumb
            if (img != null) {
                Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(
                    when (asset.type) {
                        MediaType.AUDIO -> ClipprIcons.Music
                        MediaType.IMAGE -> ClipprIcons.Photo
                        MediaType.VIDEO -> ClipprIcons.Film
                    },
                    contentDescription = null,
                    tint = c.textTertiary,
                )
            }
            if (asset.durationUs > 0) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(c.background.copy(alpha = 0.78f)).padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(Timecode.formatDuration(asset.durationUs), style = MaterialTheme.typography.labelSmall, color = c.textPrimary)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(asset.displayName, style = MaterialTheme.typography.labelLarge, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "In " + item.projects.joinToString(", ") { it.second },
            style = MaterialTheme.typography.bodySmall,
            color = c.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// Templates --------------------------------------------------------------------------------------

@Composable
internal fun TemplatesTab(columns: Int, query: String, busy: Boolean, onUse: (ProjectTemplate) -> Unit) {
    val c = palette
    val context = LocalContext.current
    val fonts = remember { RenderResources.get(context).fonts }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        Column {
            Text("Templates", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
            Text(
                "Start with the right format, or with titles already in place. Everything stays editable.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textTertiary,
            )
        }
        TemplateCategory.entries.forEach { category ->
            val shown = ProjectTemplates.all.filter { it.category == category }
                .filter { query.isBlank() || it.name.contains(query.trim(), true) || it.description.contains(query.trim(), true) }
            if (shown.isNotEmpty()) {
                Text(category.label, style = MaterialTheme.typography.titleMedium, color = c.textSecondary)
                GridRows(shown, if (category == TemplateCategory.FORMATS) columns + 1 else columns, spacing = Spacing.md) { t, m ->
                    val family = remember(t.cardFontId) { FontFamily(ComposeTypeface(fonts.typeface(t.cardFontId, true))) }
                    TemplateCard(t, family, enabled = !busy, onClick = { onUse(t) }, modifier = m)
                }
            }
        }
    }
}

@Composable
private fun TemplateCard(template: ProjectTemplate, family: FontFamily, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = palette
    val (bg, accent) = template.colors
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface)
            .border(1.dp, c.hairline, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Use template ${template.name}", onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(if (template.category == TemplateCategory.FORMATS) 1.2f else 16f / 10f)
                .background(Brush.linearGradient(listOf(Color(bg), Color(bg).copy(alpha = 0.6f), Color(accent).copy(alpha = 0.35f)))),
            contentAlignment = Alignment.Center,
        ) {
            if (template.category == TemplateCategory.FORMATS) {
                // The frame shape at true proportions.
                val aspect = template.aspect.width.toFloat() / template.aspect.height
                val box = 64.dp
                val (w, h) = if (aspect >= 1f) box to box / aspect else box * aspect to box
                Box(Modifier.size(w, h).border(2.dp, Color(accent), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                    Text(template.cardTitle, color = Color(accent), style = MaterialTheme.typography.labelLarge)
                }
            } else {
                Text(
                    template.cardTitle,
                    fontFamily = family,
                    fontSize = 26.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                Box(
                    Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(template.aspect.label, style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(template.name, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(template.description, style = MaterialTheme.typography.bodySmall, color = c.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// Search -----------------------------------------------------------------------------------------

/** Results across projects and templates while the search field has text. */
@Composable
internal fun SearchResults(query: String, projects: List<ProjectSummary>, columns: Int, actions: HomeActions, onUseTemplate: (ProjectTemplate) -> Unit) {
    val c = palette
    val q = query.trim()
    val matchingProjects = projects.filter { it.name.contains(q, ignoreCase = true) }
    val matchingTemplates = ProjectTemplates.all.filter { it.name.contains(q, true) || it.description.contains(q, true) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        Text("Results for “$q”", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
        if (matchingProjects.isEmpty() && matchingTemplates.isEmpty()) {
            EmptyState(ClipprIcons.Search, "Nothing found", "No projects or templates match “$q”.", Modifier.fillMaxWidth())
        }
        if (matchingProjects.isNotEmpty()) {
            Text("Projects", style = MaterialTheme.typography.titleMedium, color = c.textSecondary)
            GridRows(matchingProjects, columns) { p, m ->
                ProjectCard(p, { actions.openProject(p.id) }, { actions.rename(p) }, { actions.duplicate(p.id) }, { actions.delete(p) }, m)
            }
        }
        if (matchingTemplates.isNotEmpty()) {
            Text("Templates", style = MaterialTheme.typography.titleMedium, color = c.textSecondary)
            matchingTemplates.forEach { t ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface).clickable(role = Role.Button) { onUseTemplate(t) }.padding(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(ClipprIcons.Grid, contentDescription = null, tint = c.accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(Spacing.md))
                    Column(Modifier.weight(1f)) {
                        Text(t.name, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                        Text(t.description, style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                    }
                    Text("Use", style = MaterialTheme.typography.labelLarge, color = c.accent)
                }
            }
        }
    }
}
