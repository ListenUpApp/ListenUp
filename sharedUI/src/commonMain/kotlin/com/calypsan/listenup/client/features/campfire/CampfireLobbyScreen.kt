package com.calypsan.listenup.client.features.campfire

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calypsan.listenup.api.dto.campfire.CampfireInvitableUser
import com.calypsan.listenup.api.dto.campfire.CampfireMember
import com.calypsan.listenup.client.design.components.AvatarSize
import com.calypsan.listenup.client.design.components.UserAvatar
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.campfire_flow_lobby_eyebrow
import listenup.composeapp.generated.resources.campfire_flow_lobby_gathered
import listenup.composeapp.generated.resources.campfire_flow_lobby_headline
import listenup.composeapp.generated.resources.campfire_flow_lobby_host_badge
import listenup.composeapp.generated.resources.campfire_flow_lobby_invited_pending
import listenup.composeapp.generated.resources.campfire_flow_lobby_latecomers
import listenup.composeapp.generated.resources.campfire_flow_lobby_start_cta
import listenup.composeapp.generated.resources.campfire_flow_lobby_subcopy
import listenup.composeapp.generated.resources.campfire_flow_lobby_waiting_for_host
import org.jetbrains.compose.resources.stringResource

/**
 * Screen 3 of the full-screen Campfire flow — the lobby ("Warming up"), rendered while
 * [com.calypsan.listenup.api.dto.campfire.CampfirePhase.LOBBY] (co-listening design spec's
 * 2026-07-11 lobby amendment, task L3). Two variants sharing one layout: the host sees the "Start
 * listening together" CTA; every other member sees "Waiting for {host} to start" instead — a guest
 * variant originated for this task (not in the design package, which only mocked the host view).
 *
 * @param members Current joined roster (full-opacity, coral-ring avatars).
 * @param invitedPending Invited-but-not-joined users (dim ring, "invited…" caption) — see
 * [com.calypsan.listenup.api.dto.campfire.CampfireSnapshot.invitedPending].
 * @param isHost Whether the local caller is the host — gates the Start CTA.
 * @param onStart Host-only — starts the campfire (`CampfireViewModel.startCampfire`).
 */
@Composable
internal fun CampfireLobbyScreen(
    campfireName: String,
    bookTitle: String,
    members: List<CampfireMember>,
    invitedPending: List<CampfireInvitableUser>,
    hostUserId: String,
    hostDisplayName: String,
    isHost: Boolean,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
) {
    CampfireBackdrop(
        stage = CampfireBackdropStage.STEADY,
        modifier = modifier.fillMaxSize(),
        reducedMotion = reducedMotion,
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CampfireFlowHeader(
                    eyebrow = stringResource(Res.string.campfire_flow_lobby_eyebrow),
                    title = campfireName,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .widthIn(max = CampfireFlowContentMaxWidth)
                        .wrapContentWidth()
                        .padding(horizontal = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CampfireGlassCard(shape = RoundedCornerShape(percent = 50)) {
                    Text(
                        text = stringResource(Res.string.campfire_flow_lobby_gathered, members.size, bookTitle),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                        color = CampfireFlowColors.OnGlass,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                }

                Spacer(Modifier.height(22.dp))

                Text(
                    text = stringResource(Res.string.campfire_flow_lobby_headline),
                    color = CampfireFlowColors.OnGlass,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 26.sp,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(Res.string.campfire_flow_lobby_subcopy),
                    color = CampfireFlowColors.OnGlassMuted,
                    fontSize = 14.5.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, bottom = 30.dp),
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    members.forEach { member ->
                        LobbyRosterAvatar(
                            userId = member.userId,
                            displayName = member.displayName ?: member.userId,
                            isHost = member.userId == hostUserId,
                            joined = true,
                        )
                    }
                    invitedPending.forEach { pending ->
                        LobbyRosterAvatar(
                            userId = pending.userId,
                            displayName = pending.displayName,
                            isHost = false,
                            joined = false,
                        )
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(
                    modifier =
                        Modifier
                            .widthIn(max = CampfireFlowContentMaxWidth)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (isHost) {
                        CampfireFireButton(
                            text = stringResource(Res.string.campfire_flow_lobby_start_cta),
                            onClick = onStart,
                            leadingIcon = {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White,
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = stringResource(Res.string.campfire_flow_lobby_latecomers),
                            color = CampfireFlowColors.OnGlassFaint,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    } else {
                        Text(
                            text = stringResource(Res.string.campfire_flow_lobby_waiting_for_host, hostDisplayName),
                            color = CampfireFlowColors.OnGlassMuted,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LobbyRosterAvatar(
    userId: String,
    displayName: String,
    isHost: Boolean,
    joined: Boolean,
) {
    val ringColor = if (joined) CampfireFlowColors.Coral.copy(alpha = 0.7f) else CampfireFlowColors.OnGlassFaint
    // Column width tracks the avatar's own diameter so the square avatar is never clamped narrower
    // than it is tall — a non-square box under CircleShape renders as a pill, not a circle.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(AvatarSize.Large.dp).alpha(if (joined) 1f else 0.55f),
    ) {
        Box(contentAlignment = Alignment.BottomCenter) {
            UserAvatar(
                userId = userId,
                size = AvatarSize.Large,
                fallbackName = displayName,
                modifier = Modifier.border(2.dp, ringColor, CircleShape),
            )
            if (isHost) {
                Box(
                    modifier =
                        Modifier
                            .offset(y = 4.dp)
                            .background(CampfireFlowColors.Coral, RoundedCornerShape(percent = 50)),
                ) {
                    Text(
                        text = stringResource(Res.string.campfire_flow_lobby_host_badge),
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 8.5.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
        }
        Text(
            text = displayName,
            color = if (joined) CampfireFlowColors.OnGlass else CampfireFlowColors.OnGlassMuted,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (!joined) {
            Text(
                text = stringResource(Res.string.campfire_flow_lobby_invited_pending),
                color = CampfireFlowColors.OnGlassFaint,
                fontSize = 10.sp,
            )
        }
    }
}
