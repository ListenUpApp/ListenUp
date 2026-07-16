package com.calypsan.listenup.client.features.campfire

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calypsan.listenup.client.design.components.AvatarSize
import com.calypsan.listenup.client.design.components.UserAvatar
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.campfire_flow_room_chat_placeholder
import org.jetbrains.compose.resources.stringResource

/** The six curated reactions offered on every Campfire room (co-listening design spec §5). */
internal val CAMPFIRE_ROOM_REACTIONS = listOf("❤️", "😂", "😮", "😢", "👏", "🔥")

/** Cap on how many recent rows the ambient feed renders — older history simply isn't kept. */
private const val AMBIENT_FEED_VISIBLE_COUNT = 14

/**
 * One row of the live Room's ambient chat feed — absorbs [com.calypsan.listenup.api.dto.campfire.ChatMessage]s
 * and locally-synthesized join/leave/host-change rows. There is no server-pushed "system event"
 * frame shape to render directly here; `CampfireRoomScreen` builds [System] rows from
 * [com.calypsan.listenup.api.dto.campfire.CampfireFrame.MemberJoined]/
 * [com.calypsan.listenup.api.dto.campfire.CampfireFrame.MemberLeft]/
 * [com.calypsan.listenup.api.dto.campfire.CampfireFrame.HostChanged] deltas observed on
 * [com.calypsan.listenup.client.presentation.campfire.CampfireScreenUiState.Active.members]/`hostUserId`.
 */
internal sealed interface CampfireFeedRow {
    val key: String

    /** A chat message row — [senderId]/[senderName] resolve the sender, [isSelf] highlights the local caller's own bubble. */
    data class Message(
        val senderId: String,
        val senderName: String,
        val text: String,
        val isSelf: Boolean,
        override val key: String,
    ) : CampfireFeedRow

    /** A locally-synthesized join/leave/host-change row (see [CampfireFeedRow]'s KDoc). */
    data class System(
        val text: String,
        override val key: String,
    ) : CampfireFeedRow
}

/**
 * The live Room's always-on ambient chat overlay (co-listening design spec §5, task L3) — absorbs
 * the Task-10 `CampfireChatSheet`: the last [AMBIENT_FEED_VISIBLE_COUNT] rows of [feed], a message
 * input row, and a quick-react control that expands into the 6-emoji [CAMPFIRE_ROOM_REACTIONS] picker.
 *
 * @param onQuickReact Called with the tapped emoji whenever the picker or the quick-react button fires.
 */
@Composable
internal fun CampfireAmbientChat(
    feed: List<CampfireFeedRow>,
    onSend: (String) -> Unit,
    onQuickReact: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf("") }
    var pickerOpen by remember { mutableStateOf(false) }
    var quickEmoji by remember { mutableStateOf(CAMPFIRE_ROOM_REACTIONS.last()) }
    val listState = remember { LazyListState() }
    val visible = remember(feed) { feed.takeLast(AMBIENT_FEED_VISIBLE_COUNT) }

    LaunchedEffect(visible.size) {
        if (visible.isNotEmpty()) listState.animateScrollToItem(visible.lastIndex)
    }

    fun send() {
        val text = draft.trim()
        if (text.isNotEmpty()) {
            onSend(text)
            draft = ""
        }
    }

    Column(modifier = modifier) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
        ) {
            items(visible, key = { it.key }) { row -> CampfireFeedRowView(row) }
        }

        Spacer(Modifier.height(10.dp))

        if (pickerOpen) {
            CampfireGlassCard(shape = CircleShape) {
                Row(modifier = Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    CAMPFIRE_ROOM_REACTIONS.forEach { emoji ->
                        Box(
                            modifier =
                                Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        quickEmoji = emoji
                                        onQuickReact(emoji)
                                        pickerOpen = false
                                    },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text = emoji, fontSize = 22.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text(stringResource(Res.string.campfire_flow_room_chat_placeholder)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = CircleShape,
                trailingIcon = {
                    Box(
                        modifier =
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(
                                    if (draft.isNotBlank()) MaterialTheme.colorScheme.primary else Color(0x1FFFFFFF),
                                ).clickable(enabled = draft.isNotBlank()) { send() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                },
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedTextColor = CampfireFlowColors.OnGlass,
                        unfocusedTextColor = CampfireFlowColors.OnGlass,
                        focusedContainerColor = CampfireFlowColors.Glass.copy(alpha = 0.55f),
                        unfocusedContainerColor = CampfireFlowColors.Glass.copy(alpha = 0.55f),
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = CampfireFlowColors.GlassBorder,
                        unfocusedPlaceholderColor = CampfireFlowColors.OnGlassFaint,
                        focusedPlaceholderColor = CampfireFlowColors.OnGlassFaint,
                        cursorColor = MaterialTheme.colorScheme.primary,
                    ),
            )
            val addButtonColor =
                if (pickerOpen) {
                    MaterialTheme.colorScheme.primary.copy(
                        alpha = 0.3f,
                    )
                } else {
                    CampfireFlowColors.Glass.copy(alpha = 0.55f)
                }
            Box(
                modifier =
                    Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(addButtonColor)
                        .clickable { pickerOpen = !pickerOpen },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Box(
                modifier =
                    Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f))
                        .clickable { onQuickReact(quickEmoji) },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = quickEmoji, fontSize = 22.sp)
            }
        }
    }
}

@Composable
private fun CampfireFeedRowView(row: CampfireFeedRow) {
    when (row) {
        is CampfireFeedRow.System -> {
            Box(
                modifier =
                    Modifier.background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                        RoundedCornerShape(percent = 50),
                    ),
            ) {
                Text(
                    text = row.text,
                    color = Color(0xFFFFD9A8),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                )
            }
        }

        is CampfireFeedRow.Message -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                UserAvatar(userId = row.senderId, size = AvatarSize.Mini, fallbackName = row.senderName)
                Box(
                    modifier =
                        Modifier
                            .widthIn(max = 260.dp)
                            .background(Color(0x852E2438), RoundedCornerShape(15.dp))
                            .padding(horizontal = 11.dp, vertical = 6.dp),
                ) {
                    Column {
                        Text(
                            text = row.senderName,
                            color = if (row.isSelf) MaterialTheme.colorScheme.primary else CampfireFlowColors.OnGlass,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                        )
                        Text(
                            text = row.text,
                            color = CampfireFlowColors.OnGlass.copy(alpha = 0.94f),
                            fontSize = 13.5.sp,
                        )
                    }
                }
            }
        }
    }
}
