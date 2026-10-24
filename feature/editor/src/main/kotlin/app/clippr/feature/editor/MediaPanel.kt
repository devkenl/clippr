package app.clippr.feature.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.clippr.core.media.ThumbnailRepository
import app.clippr.core.model.MediaAsset
import app.clippr.core.model.MediaType
import app.clippr.core.model.Project
import app.clippr.core.model.time.Timecode
import app.clippr.core.ui.components.EmptyState
import app.clippr.core.ui.components.FilterChip
import app.clippr.core.ui.components.PrimaryButton
import app.clippr.core.ui.icons.ClipprIcons
import app.clippr.core.ui.theme.Spacing
import app.clippr.core.ui.theme.palette

private enum class MediaFilter(val label: String) { ALL("All"), VIDEO("Videos"), PHOTO("Photos"), AUDIO("Audio") }

/**
 * Imported media for the project. Tap an item to place it at the playhead. Missing files show a
 * relink action. The Text tab adds titles.
 */
@Composable
fun MediaPanel(
    project: Project,
    missingAssetIds: Set<String>,
    recentAssetIds: Set<String>,
    importing: Boolean,
    thumbnails: ThumbnailRepository,
    onImport: () -> Unit,
    onAdd: (MediaAsset) -> Unit,
    onRelink: (MediaAsset) -> Unit,
    onAddText: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = palette
    var tab by rememberSaveable { mutableStateOf(0) }
    var filter by rememberSaveable { mutableStateOf(MediaFilter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }

    Column(modifier.background(c.surface)) {
        Row(Modifier.fillMaxWidth().padding(Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FilterChip("Media", tab == 0, { tab = 0 })
            FilterChip("Text", tab == 1, { tab = 1 })
        }
        if (tab == 1) {
            TextTab(onAddText)
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            SearchField(query, { query = it }, Modifier.weight(1f))
            Spacer(Modifier.size(Spacing.sm))
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(c.accent)
                    .clickable(enabled = !importing, role = Role.Button, onClickLabel = "Import media", onClick = onImport),
                contentAlignment = Alignment.Center,
            ) {
                Icon(ClipprIcons.Import, contentDescription = "Import media", tint = c.onAccent, modifier = Modifier.size(20.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            MediaFilter.entries.forEach { f -> FilterChip(f.label, filter == f, { filter = f }) }
        }

        val assets = project.assets
            .filter {
                when (filter) {
                    MediaFilter.ALL -> true
                    MediaFilter.VIDEO -> it.type == MediaType.VIDEO
                    MediaFilter.PHOTO -> it.type == MediaType.IMAGE
                    MediaFilter.AUDIO -> it.type == MediaType.AUDIO
                }
            }
            .filter { query.isBlank() || it.displayName.contains(query.trim(), ignoreCase = true) }

        when {
            importing && project.assets.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Reading media…", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            }
            project.assets.isEmpty() -> EmptyState(
                icon = ClipprIcons.Import,
                title = "No media yet",
                body = "Import videos, photos and music from your device. Files stay where they are; Clippr only reads them.",
                modifier = Modifier.fillMaxSize(),
                action = { PrimaryButton("Import media", onImport, icon = ClipprIcons.Import, enabled = !importing) },
            )
            assets.isEmpty() -> Box(Modifier.fillMaxSize().padding(Spacing.xl), contentAlignment = Alignment.TopCenter) {
                Text("Nothing matches.", style = MaterialTheme.typography.bodyMedium, color = c.textTertiary)
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(120.dp),
                contentPadding = PaddingValues(start = Spacing.md, end = Spacing.md, bottom = Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(assets, key = { it.id }) { asset ->
                    MediaTile(
                        asset = asset,
                        missing = asset.id in missingAssetIds,
                        recent = asset.id in recentAssetIds,
                        used = project.sequence.allClips.any { it.assetId == asset.id },
                        thumbnails = thumbnails,
                        onAdd = { onAdd(asset) },
                        onRelink = { onRelink(asset) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier) {
    val c = palette
    Row(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(c.panel)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ClipprIcons.Search, contentDescription = null, tint = c.textTertiary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Search media", style = MaterialTheme.typography.bodyMedium, color = c.textTertiary)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.textPrimary),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun MediaTile(
    asset: MediaAsset,
    missing: Boolean,
    recent: Boolean,
    used: Boolean,
    thumbnails: ThumbnailRepository,
    onAdd: () -> Unit,
    onRelink: () -> Unit,
) {
    val c = palette
    val thumb by produceState<Bitmap?>(null, asset.uri, missing) {
        value = if (missing || asset.type == MediaType.AUDIO) null else thumbnails.frame(asset, 0, 240)
    }
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClickLabel = if (missing) "Relink ${asset.displayName}" else "Add ${asset.displayName} at playhead") {
                if (missing) onRelink() else onAdd()
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(10.dp))
                .background(if (asset.type == MediaType.AUDIO) c.audioClip else c.panel)
                .border(1.dp, if (recent) c.accent.copy(alpha = 0.6f) else c.hairline, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = thumb
            if (bmp != null) {
                Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(
                    when (asset.type) {
                        MediaType.VIDEO -> ClipprIcons.Film
                        MediaType.IMAGE -> ClipprIcons.Photo
                        MediaType.AUDIO -> ClipprIcons.Music
                    },
                    contentDescription = null,
                    tint = if (asset.type == MediaType.AUDIO) c.waveform else c.textTertiary,
                )
            }
            if (missing) {
                Box(Modifier.fillMaxSize().background(c.missingMedia.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(ClipprIcons.Warning, contentDescription = null, tint = c.danger, modifier = Modifier.size(20.dp))
                        Text("Tap to relink", style = MaterialTheme.typography.labelSmall, color = c.textPrimary)
                    }
                }
            } else {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    if (asset.type != MediaType.IMAGE) {
                        Text(Timecode.formatDuration(asset.durationUs), style = MaterialTheme.typography.labelSmall, color = Color.White)
                    }
                }
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(c.background.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (used) ClipprIcons.Check else ClipprIcons.Plus, contentDescription = null, tint = if (used) c.success else c.textPrimary, modifier = Modifier.size(14.dp))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            asset.displayName,
            style = MaterialTheme.typography.labelMedium,
            color = c.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

@Composable
private fun TextTab(onAddText: () -> Unit) {
    val c = palette
    Column(Modifier.fillMaxWidth().padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text("Titles", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 7f)
                .clip(RoundedCornerShape(12.dp))
                .background(c.panel)
                .border(1.dp, c.hairline, RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClickLabel = "Add title at playhead", onClick = onAddText),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Your title", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
                Spacer(Modifier.height(4.dp))
                Text("Tap to add at the playhead", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
            }
        }
        Text(
            "Edit wording, size, colour and position in the inspector after adding.",
            style = MaterialTheme.typography.bodySmall,
            color = c.textTertiary,
        )
    }
}
