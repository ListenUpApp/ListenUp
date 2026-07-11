package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.dto.campfire.CampfireControlMode
import com.calypsan.listenup.api.dto.campfire.CampfireMember
import com.calypsan.listenup.client.design.components.AvatarSize
import com.calypsan.listenup.client.design.components.UserAvatar
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.campfire_has_the_remote
import listenup.composeapp.generated.resources.campfire_open_chat
import org.jetbrains.compose.resources.stringResource

// Away members read as present-but-dimmed rather than vanishing from the roster.
private const val AWAY_MEMBER_ALPHA = 0.4f

// Cap the visible avatar row so a full 8-member room doesn't overflow the pill.
private const val MAX_VISIBLE_AVATARS = 5

/**
 * Session chrome for an active Campfire (co-listening) room, rendered on the Now Playing screen
 * (campfire implementation plan, Task 10). A floating pill: member avatars (away members dimmed),
 * a "[host] has the remote" indicator under [CampfireControlMode.HOST_ONLY], and a chat toggle.
 *
 * Never rendered when no session is active for the currently playing book — session chrome is
 * strictly additive, zero visual change otherwise (see the type's call site).
 *
 * @param members Current room roster.
 * @param hostUserId The current host's user id, used to resolve the "has the remote" name.
 * @param controlMode The room's current control mode — the remote indicator only shows under
 * [CampfireControlMode.HOST_ONLY].
 * @param onOpenChat Called when the chat icon is tapped.
 */
@Composable
fun CampfireSessionBar(
    members: List<CampfireMember>,
    hostUserId: String,
    controlMode: CampfireControlMode,
    onOpenChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp).height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                members.take(MAX_VISIBLE_AVATARS).forEach { member ->
                    UserAvatar(
                        userId = member.userId,
                        size = AvatarSize.Mini,
                        fallbackName = member.displayName,
                        modifier = Modifier.alpha(if (member.isAway) AWAY_MEMBER_ALPHA else 1f),
                    )
                }
            }

            if (controlMode == CampfireControlMode.HOST_ONLY) {
                val hostName = members.firstOrNull { it.userId == hostUserId }?.displayName ?: hostUserId
                Text(
                    text = stringResource(Res.string.campfire_has_the_remote, hostName),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(120.dp),
                )
            }

            IconButton(onClick = onOpenChat) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Chat,
                    contentDescription = stringResource(Res.string.campfire_open_chat),
                )
            }
        }
    }
}
