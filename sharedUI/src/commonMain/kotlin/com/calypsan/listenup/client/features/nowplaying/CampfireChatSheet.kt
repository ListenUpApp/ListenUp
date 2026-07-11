package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.dto.campfire.CampfireMember
import com.calypsan.listenup.api.dto.campfire.ChatMessage
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.features.nowplaying.components.PlayerPanelScaffold
import kotlinx.coroutines.launch
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.campfire_chat_at_position
import listenup.composeapp.generated.resources.campfire_chat_input_placeholder
import listenup.composeapp.generated.resources.campfire_chat_title
import listenup.composeapp.generated.resources.campfire_chat_unknown_member
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

/** The six curated reactions offered on every Campfire room (co-listening design spec §5). */
val CAMPFIRE_REACTION_EMOJI = listOf("❤️", "😂", "😮", "😢", "👏", "🔥")

/**
 * Chat sheet for an active Campfire session (co-listening design spec §5, campfire implementation
 * plan Task 10). Lists [messages] with their `positionMs` rendered as "at H:MM:SS", an input row,
 * and the curated reaction bar.
 *
 * @param messages The room's chat log, oldest first.
 * @param members Current roster, used to resolve each message's sender display name.
 * @param onSend Called with the trimmed message text when the user sends a non-blank message.
 * @param onReaction Called with the tapped reaction emoji.
 * @param onDismiss Dismiss the sheet.
 */
@Composable
fun CampfireChatSheet(
    messages: List<ChatMessage>,
    members: List<CampfireMember>,
    onSend: (String) -> Unit,
    onReaction: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val unknownMemberLabel = stringResource(Res.string.campfire_chat_unknown_member)

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    PlayerPanelScaffold(
        title = stringResource(Res.string.campfire_chat_title),
        onDismiss = onDismiss,
        dialogWidth = 480.dp,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(messages) { message ->
                ChatMessageRow(
                    message = message,
                    senderName =
                        members.firstOrNull { it.userId == message.senderId }?.displayName ?: unknownMemberLabel,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        ReactionRow(onReaction = onReaction)

        Spacer(Modifier.height(12.dp))

        ListenUpTextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = stringResource(Res.string.campfire_chat_input_placeholder),
            trailingIcon = Icons.AutoMirrored.Filled.Send,
            onTrailingClick = {
                val text = draft.trim()
                if (text.isNotEmpty()) {
                    onSend(text)
                    draft = ""
                    scope.launch { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ChatMessageRow(
    message: ChatMessage,
    senderName: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = senderName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text =
                    stringResource(
                        Res.string.campfire_chat_at_position,
                        message.positionMs.milliseconds.formatPlaybackTime(),
                    ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(text = message.text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The curated reaction bar — see [CAMPFIRE_REACTION_EMOJI] (co-listening design spec §5). */
@Composable
fun ReactionRow(
    onReaction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        CAMPFIRE_REACTION_EMOJI.forEach { emoji ->
            Surface(
                onClick = { onReaction(emoji) },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.height(44.dp),
            ) {
                Box(modifier = Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                    Text(text = emoji, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
