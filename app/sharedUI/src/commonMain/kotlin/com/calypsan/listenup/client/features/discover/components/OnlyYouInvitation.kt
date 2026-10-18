package com.calypsan.listenup.client.features.discover.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.AvatarSize
import com.calypsan.listenup.client.design.components.UserAvatar
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.discover.DiscoverViewer
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_invite_someone
import listenup.composeapp.generated.resources.discover_only_you_body
import listenup.composeapp.generated.resources.discover_only_you_title
import org.jetbrains.compose.resources.stringResource

private val INVITE_AVATAR = 40.dp
private val GHOST_OFFSET = (-10).dp
private val DASH_ON = 6f
private val DASH_OFF = 6f

/**
 * Listening now when you are the only person on the server (spec §3.8): your avatar beside two dashed
 * circles for the people not here yet, what Discover will show once they are, and — for an admin only —
 * **Invite someone**. A member sees no button and no gap where it was.
 *
 * @param viewer Whose avatar leads; null omits the avatar rather than draw a blank circle.
 * @param onInvite Opens the create-invite screen; null hides the button (members, or no route).
 */
@Composable
internal fun OnlyYouInvitation(
    viewer: DiscoverViewer?,
    onInvite: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(28.dp))
                .padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Row(Modifier.clearAndSetSemantics {}) {
            if (viewer != null) {
                UserAvatar(
                    userId = viewer.userId,
                    size = AvatarSize.Medium,
                    fallbackName = viewer.displayName,
                    modifier = Modifier.size(INVITE_AVATAR),
                )
            }
            repeat(2) { index ->
                val outline = MaterialTheme.colorScheme.outline
                Box(
                    Modifier
                        .offset(x = GHOST_OFFSET * (index + if (viewer != null) 1 else 0))
                        .size(INVITE_AVATAR)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                        .drawBehind {
                            drawCircle(
                                color = outline,
                                radius = size.minDimension / 2 - 1.dp.toPx(),
                                style =
                                    Stroke(
                                        width = 2.dp.toPx(),
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH_ON, DASH_OFF)),
                                    ),
                            )
                        },
                )
            }
        }
        Text(
            text = stringResource(Res.string.discover_only_you_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(Res.string.discover_only_you_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onInvite != null) {
            FilledTonalButton(
                onClick = {
                    haptics.press()
                    onInvite()
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(Res.string.admin_invite_someone))
            }
        }
    }
}
