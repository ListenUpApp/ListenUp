package com.calypsan.listenup.client.features.storyworld.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.presentation.storyworld.composer.AssertionUi
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.story_world_assertion_dismiss

private val TILE_SIZE = 34.dp
private const val TILE_TINT_ALPHA = 0.16f
private const val TILE_ICON_RATIO = 0.55f

/**
 * The composer's detected-assertion chip: a type-tinted icon tile (the same [iconAndTint] mapping
 * [HubEventRow] uses) plus "subjectName · objectName?", with a trailing dismiss action that drops
 * back to a plain [com.calypsan.listenup.api.sync.WorldEventType.NOTE].
 */
@Composable
fun AssertionChip(
    assertion: AssertionUi,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (icon, tint) = assertion.type.iconAndTint()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = tint.copy(alpha = TILE_TINT_ALPHA).compositeOver(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(TILE_SIZE)
                        .clip(RoundedCornerShape(TILE_SIZE / 3))
                        .background(
                            tint.copy(alpha = 0.22f).compositeOver(MaterialTheme.colorScheme.surfaceContainerHigh),
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(TILE_SIZE * TILE_ICON_RATIO),
                )
            }
            Text(
                text = listOfNotNull(assertion.subjectName, assertion.objectName).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(Res.string.story_world_assertion_dismiss),
                )
            }
        }
    }
}
