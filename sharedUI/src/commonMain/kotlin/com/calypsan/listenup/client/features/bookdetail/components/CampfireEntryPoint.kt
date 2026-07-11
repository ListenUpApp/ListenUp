package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.campfire_entry_point
import org.jetbrains.compose.resources.stringResource

/**
 * Floating entry point for Campfire (co-listening) on the Book Detail screen (campfire
 * implementation plan, Task 10) — a fire icon, always available so a listener can host a
 * session, with a small count badge when [liveMemberCount] is positive (someone is listening
 * right now). Tapping opens [CampfireBookSheet].
 */
@Composable
fun CampfireEntryPoint(
    liveMemberCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(48.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.LocalFireDepartment,
                    contentDescription = stringResource(Res.string.campfire_entry_point),
                    tint =
                        if (liveMemberCount >
                            0
                        ) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        }

        if (liveMemberCount > 0) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp).align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = liveMemberCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}
