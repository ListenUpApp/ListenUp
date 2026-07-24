package com.calypsan.listenup.client.features.setup.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calypsan.listenup.api.dto.DirectoryEntry
import com.calypsan.listenup.client.design.components.cookieScallopShape
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.library_setup_item_count
import listenup.composeapp.generated.resources.library_setup_item_count_one
import listenup.composeapp.generated.resources.library_setup_open_folder
import org.jetbrains.compose.resources.stringResource

/** Soft decorative blob echoing the design hero. Purely cosmetic. */
@Composable
fun SetupHeroBlob(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .size(210.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)),
    )
}

/**
 * Breadcrumb for the current browse path. The leading "/" chip is the filesystem
 * root; subsequent segments are split from [path]. The last segment is brand-coloured.
 */
@Composable
fun SetupBreadcrumb(
    path: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val segments = path.split('/').filter { it.isNotBlank() }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .height(36.dp)
                    .clip(CircleShape)
                    .clickable { onNavigate("/") }
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(start = 12.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Home,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp),
            )
            Text(
                text = "/",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        segments.forEachIndexed { index, segment ->
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = segment,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color =
                    if (index == segments.lastIndex) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { onNavigate("/" + segments.take(index + 1).joinToString("/")) },
            )
        }
    }
}

/** The M3 checkbox square: filled brand + white check when [on], outline ring otherwise. */
@Composable
fun SetupCheckbox(
    on: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .then(
                    if (on) {
                        Modifier.background(MaterialTheme.colorScheme.primary)
                    } else {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    },
                ),
        contentAlignment = Alignment.Center,
    ) {
        if (on) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * One folder row: icon tile + name + "N items" + checkbox + chevron. When [selected]
 * the whole row sits on a [primaryContainer] highlight. The chevron only shows when
 * [entry] has children to drill into.
 */
@Composable
fun FolderRow(
    entry: DirectoryEntry,
    selected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val rowBackground = if (selected) scheme.primaryContainer else Color.Transparent
    val tileBackground = if (selected) scheme.primary.copy(alpha = 0.22f) else scheme.surfaceContainerHigh
    val tileTint = if (selected) scheme.primary else scheme.onSurfaceVariant
    val titleColor = if (selected) scheme.onPrimaryContainer else scheme.onSurface
    val subtitleColor = if (selected) scheme.onPrimaryContainer.copy(alpha = 0.85f) else scheme.onSurfaceVariant
    val chevronTint = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(rowBackground)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(tileBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Folder,
                contentDescription = null,
                tint = tileTint,
                modifier = Modifier.size(26.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.titleMedium,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    stringResource(
                        if (entry.itemCount ==
                            1
                        ) {
                            Res.string.library_setup_item_count_one
                        } else {
                            Res.string.library_setup_item_count
                        },
                        entry.itemCount,
                    ),
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 13.sp,
                color = subtitleColor,
            )
        }
        Box(modifier = Modifier.clip(CircleShape).clickableNoRipple(onToggle).padding(2.dp)) {
            SetupCheckbox(on = selected)
        }
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (entry.hasChildren) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = stringResource(Res.string.library_setup_open_folder),
                    tint = chevronTint,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/**
 * The celebratory scalloped check used on the confirmation screen. Backed by
 * [MaterialShapes.Cookie9Sided] — the same scallop used for contributor avatars.
 */
@Composable
fun ScallopBadge(
    size: Dp,
    fill: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.size(size).clip(cookieScallopShape()).background(fill),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

/** Summary card for a created library: icon tile + name + "N folder · path" + tertiary check. */
@Composable
fun LibrarySummaryCard(
    name: String,
    folderCount: Int,
    firstPath: String?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.LibraryBooks,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(28.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val folderLabel = if (folderCount == 1) "1 folder" else "$folderCount folders"
            Text(
                text = if (firstPath != null) "$folderLabel · $firstPath" else folderLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier =
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )
