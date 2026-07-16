package com.calypsan.listenup.client.features.campfire

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calypsan.listenup.api.dto.campfire.CampfireMember
import com.calypsan.listenup.client.design.components.AvatarSize
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.components.UserAvatar
import com.calypsan.listenup.client.features.nowplaying.components.CampfireReactionOverlay
import com.calypsan.listenup.client.features.nowplaying.components.FloatingReaction
import com.calypsan.listenup.client.presentation.campfire.CampfireScreenUiState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.campfire_flow_room_around_fire
import listenup.composeapp.generated.resources.campfire_flow_room_everyone_controls
import listenup.composeapp.generated.resources.campfire_flow_room_invite
import listenup.composeapp.generated.resources.campfire_flow_room_leave
import listenup.composeapp.generated.resources.campfire_flow_room_live
import listenup.composeapp.generated.resources.campfire_flow_room_others
import listenup.composeapp.generated.resources.campfire_flow_room_others_two
import listenup.composeapp.generated.resources.campfire_flow_room_system_host_changed
import listenup.composeapp.generated.resources.campfire_flow_room_system_joined
import listenup.composeapp.generated.resources.campfire_flow_room_system_left
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

// Roster avatar stack caps at this many faces before folding the rest into the count text.
private const val MAX_STACK_AVATARS = 6

/**
 * Screen 4 of the full-screen Campfire flow — the live co-listening Room, rendered while
 * [com.calypsan.listenup.api.dto.campfire.CampfirePhase.LIVE] (co-listening design spec's
 * 2026-07-11 lobby amendment, task L3). Evolves the Task-10 session chrome (`CampfireSessionBar` +
 * `CampfireChatSheet`, both retired) into a dedicated full-screen room: a top bar (leave, LIVE pill
 * + member count, invite), an avatar stack + "around the fire" line, a now-playing strip, transport
 * controls, and the always-on [CampfireAmbientChat] overlay.
 *
 * Transport buttons stay tappable regardless of [hasControl] — under
 * [com.calypsan.listenup.api.dto.campfire.CampfireControlMode.HOST_ONLY] without the remote, a tap
 * still reaches [onPlayPause]/[onScrub], and [CampfireSessionController][com.calypsan.listenup.client.campfire.CampfireSessionController]'s
 * existing gate emits [com.calypsan.listenup.client.presentation.campfire.CampfireScreenEvent.ControlDenied]
 * (surfaced as a snackbar by the caller) — the "demoted but tappable" behavior already in place,
 * unchanged by this screen.
 *
 * @param feed The ambient chat feed — chat messages plus locally-synthesized join/leave/host-change
 * rows (built by [rememberCampfireFeed] from [session] deltas).
 */
@Suppress("LongParameterList")
@Composable
internal fun CampfireRoomScreen(
    session: CampfireScreenUiState.Active,
    book: CampfireFlowBook,
    isPlaying: Boolean,
    progressFraction: Float,
    positionLabel: String,
    remainingLabel: String,
    feed: List<CampfireFeedRow>,
    floatingReactions: List<FloatingReaction>,
    onReactionFinished: (Long) -> Unit,
    onLeave: () -> Unit,
    onInvite: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onScrub: (Float) -> Unit,
    onSend: (String) -> Unit,
    onQuickReact: (String) -> Unit,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
) {
    CampfireBackdrop(
        stage = CampfireBackdropStage.ROARING,
        modifier = modifier.fillMaxSize(),
        reducedMotion = reducedMotion,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp),
        ) {
            RoomTopBar(members = session.members, onLeave = onLeave, onInvite = onInvite)

            Spacer(Modifier.height(12.dp))

            CampfireGlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BookCoverImage(
                        bookId = book.bookId,
                        coverPath = book.coverPath,
                        coverHash = book.coverHash,
                        blurHash = book.coverBlurHash,
                        contentDescription = book.title,
                        title = book.title,
                        modifier = Modifier.size(46.dp).clip(RoundedCornerShape(9.dp)),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = book.title,
                            color = CampfireFlowColors.OnGlass,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            fontSize = 14.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (book.subtitle.isNotBlank()) {
                            Text(
                                text = book.subtitle,
                                color = CampfireFlowColors.OnGlassMuted,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (isPlaying) {
                        Icon(
                            Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                CampfireReactionOverlay(
                    reactions = floatingReactions,
                    onFinished = onReactionFinished,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
                CampfireAmbientChat(
                    feed = feed,
                    onSend = onSend,
                    onQuickReact = onQuickReact,
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(0.86f),
                )
            }

            Spacer(Modifier.height(10.dp))
            RoomTransport(
                isPlaying = isPlaying,
                progressFraction = progressFraction,
                positionLabel = positionLabel,
                remainingLabel = remainingLabel,
                onPlayPause = onPlayPause,
                onSkipBack = onSkipBack,
                onSkipForward = onSkipForward,
                onScrub = onScrub,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun RoomTopBar(
    members: List<CampfireMember>,
    onLeave: () -> Unit,
    onInvite: () -> Unit,
) {
    Column(modifier = Modifier.padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CampfireGlassIconButton(
                onClick = onLeave,
                contentDescription = stringResource(Res.string.campfire_flow_room_leave),
            )
            CampfireGlassCard(shape = RoundedCornerShape(percent = 50)) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                    Text(
                        text = stringResource(Res.string.campfire_flow_room_live),
                        color = CampfireFlowColors.OnGlass,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                        fontSize = 12.sp,
                    )
                    Text(text = members.size.toString(), color = CampfireFlowColors.OnGlass, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            CampfireGlassIconButton(
                onClick = onInvite,
                contentDescription = stringResource(Res.string.campfire_flow_room_invite),
                icon = { Icon(Icons.Default.PersonAdd, contentDescription = null, tint = CampfireFlowColors.OnGlass) },
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row {
                members.take(MAX_STACK_AVATARS).forEachIndexed { index, member ->
                    UserAvatar(
                        userId = member.userId,
                        size = AvatarSize.Small,
                        fallbackName = member.displayName,
                        modifier = Modifier.padding(start = if (index == 0) 0.dp else 22.dp),
                    )
                }
            }
            Column {
                Text(
                    text = roomRosterLabel(members),
                    color = CampfireFlowColors.OnGlass,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(Res.string.campfire_flow_room_around_fire),
                    color = CampfireFlowColors.OnGlassFaint,
                    fontSize = 11.5.sp,
                )
            }
        }
    }
}

@Composable
private fun roomRosterLabel(members: List<CampfireMember>): String {
    val names = members.map { it.displayName ?: it.userId }
    return when {
        names.isEmpty() -> ""
        names.size == 1 -> names[0]
        names.size == 2 -> stringResource(Res.string.campfire_flow_room_others_two, names[0], names[1])
        else -> stringResource(Res.string.campfire_flow_room_others, names[0], names[1], names.size - 2)
    }
}

@Composable
private fun RoomTransport(
    isPlaying: Boolean,
    progressFraction: Float,
    positionLabel: String,
    remainingLabel: String,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onScrub: (Float) -> Unit,
) {
    CampfireGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = positionLabel, color = CampfireFlowColors.OnGlassMuted, fontSize = 11.sp)
                Text(
                    text = stringResource(Res.string.campfire_flow_room_everyone_controls),
                    color = CampfireFlowColors.OnGlassFaint,
                    fontSize = 10.5.sp,
                )
                Text(text = remainingLabel, color = CampfireFlowColors.OnGlassMuted, fontSize = 11.sp)
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.Slider(
                value = progressFraction.coerceIn(0f, 1f),
                onValueChange = onScrub,
                colors =
                    androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = CampfireFlowColors.OnGlass,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor =
                            androidx.compose.ui.graphics
                                .Color(0x2EFFFFFF),
                    ),
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransportGlyph(icon = Icons.Default.Replay10, onClick = onSkipBack, size = 46.dp)
                Box(
                    modifier =
                        Modifier
                            .size(62.dp)
                            .clip(CircleShape)
                            .background(
                                androidx.compose.ui.graphics.Brush.linearGradient(
                                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary),
                                ),
                            ).clickable(onClick = onPlayPause),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(30.dp),
                    )
                }
                TransportGlyph(icon = Icons.Default.Forward30, onClick = onSkipForward, size = 46.dp)
            }
        }
    }
}

@Composable
private fun TransportGlyph(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp,
) {
    Box(
        modifier =
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(
                    androidx.compose.ui.graphics
                        .Color(0x14FFFFFF),
                ).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = CampfireFlowColors.OnGlass, modifier = Modifier.size(24.dp))
    }
}

/**
 * Builds the ambient feed's local join/leave/host-change [CampfireFeedRow.System] rows by diffing
 * [session]'s roster/host across recompositions, folded with its chat messages. There is no
 * server-pushed system-event frame to render directly (see [CampfireFeedRow]'s KDoc) — this is
 * purely a client-side presentation convenience, never fed back into
 * [com.calypsan.listenup.client.presentation.campfire.CampfireViewModel] state.
 *
 * @param selfUserId The local caller's user id — distinguishes "you" bubbles in the feed.
 */
@Composable
internal fun rememberCampfireFeed(
    session: CampfireScreenUiState.Active,
    selfUserId: String?,
): List<CampfireFeedRow> {
    val rows = remember { mutableStateListOf<CampfireFeedRow>() }
    var nextRowId by remember { mutableStateOf(0L) }
    var previousChatSize by remember { mutableStateOf(0) }
    var previousMemberIds by remember { mutableStateOf(session.members.map { it.userId }.toSet()) }
    var previousHostUserId by remember { mutableStateOf(session.hostUserId) }

    fun nextKey() = "row-${nextRowId++}"

    // New chat messages — append only the newly-arrived tail (the controller's chat log only grows).
    LaunchedEffect(session.chat.size) {
        session.chat.drop(previousChatSize).forEach { message ->
            val senderName =
                session.members.firstOrNull { it.userId == message.senderId }?.displayName ?: message.senderId
            rows +=
                CampfireFeedRow.Message(
                    senderId = message.senderId,
                    senderName = senderName,
                    text = message.text,
                    isSelf = message.senderId == selfUserId,
                    key = nextKey(),
                )
        }
        previousChatSize = session.chat.size
    }

    // Roster/host deltas — locally-synthesized system rows (see this function's KDoc).
    LaunchedEffect(session.members, session.hostUserId) {
        val currentMemberIds = session.members.map { it.userId }.toSet()
        (currentMemberIds - previousMemberIds).forEach { userId ->
            val name = session.members.firstOrNull { it.userId == userId }?.displayName ?: userId
            rows += CampfireFeedRow.System(getString(Res.string.campfire_flow_room_system_joined, name), nextKey())
        }
        (previousMemberIds - currentMemberIds).forEach { userId ->
            rows += CampfireFeedRow.System(getString(Res.string.campfire_flow_room_system_left, userId), nextKey())
        }
        if (session.hostUserId != previousHostUserId && previousMemberIds.isNotEmpty()) {
            val hostName =
                session.members.firstOrNull { it.userId == session.hostUserId }?.displayName ?: session.hostUserId
            rows +=
                CampfireFeedRow.System(
                    getString(Res.string.campfire_flow_room_system_host_changed, hostName),
                    nextKey(),
                )
        }
        previousMemberIds = currentMemberIds
        previousHostUserId = session.hostUserId
    }

    return rows
}
