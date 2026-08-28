package com.calypsan.listenup.client.features.chaptereditor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ListenUpButton
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.chapter_editor_empty_add_first
import listenup.composeapp.generated.resources.chapter_editor_empty_body
import listenup.composeapp.generated.resources.chapter_editor_empty_detect_soon
import listenup.composeapp.generated.resources.chapter_editor_empty_lookup
import listenup.composeapp.generated.resources.chapter_editor_empty_title
import org.jetbrains.compose.resources.stringResource

private val ICON_TILE = 96.dp
private val CONTENT_MAX_WIDTH = 420.dp

/**
 * The book that was never chaptered.
 *
 * The spec files this under "never stranded", and the requirement is specific: the editor opens on
 * an empty book rather than refusing to, and offers a way forward that does not depend on anything
 * working. The manual route — place the first boundary at the playhead — is the primary action
 * precisely because it needs no network, no metadata provider, and no server-side analysis.
 *
 * Lookup sits below it as the convenience, and silence detection is named but not offered: the
 * spec designs it as a Phase-2 seam, and saying so is more honest than leaving a gap where a
 * third option will later appear.
 *
 * @param onAddFirst place the first boundary at the current playhead.
 * @param onLookUp fetch a chapter set from the metadata provider.
 * @param modifier Modifier for the whole state.
 * @param canLookUp whether a lookup is possible — false offline, or with no ASIN to look up by.
 */
@Composable
fun ChapterEditorEmptyState(
    onAddFirst: () -> Unit,
    onLookUp: () -> Unit,
    modifier: Modifier = Modifier,
    canLookUp: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme

    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH),
        ) {
            Box(
                Modifier
                    .size(ICON_TILE)
                    .clip(RoundedCornerShape(28.dp))
                    .background(colors.surfaceContainerLow),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.FormatListNumbered,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(48.dp),
                )
            }

            Text(
                text = stringResource(Res.string.chapter_editor_empty_title),
                style = MaterialTheme.typography.headlineSmall,
                color = colors.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = stringResource(Res.string.chapter_editor_empty_body),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            // Manual first, and always enabled: it is the one route that cannot be unavailable.
            ListenUpButton(
                onClick = onAddFirst,
                text = stringResource(Res.string.chapter_editor_empty_add_first),
                leadingIcon = Icons.Filled.Add,
                modifier = Modifier.fillMaxWidth(),
            )
            ListenUpButton(
                onClick = onLookUp,
                text = stringResource(Res.string.chapter_editor_empty_lookup),
                leadingIcon = Icons.Outlined.CloudDownload,
                filled = false,
                enabled = canLookUp,
                modifier = Modifier.fillMaxWidth(),
            )

            // Named, not offered. The spec designs silence detection as a Phase-2 seam; leaving a
            // silent gap where it will appear reads as something missing rather than something coming.
            Row(
                Modifier
                    .padding(top = 10.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceContainerLow)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.GraphicEq,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(Res.string.chapter_editor_empty_detect_soon),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}
