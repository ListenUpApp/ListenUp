package com.calypsan.listenup.client.features.campfire

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calypsan.listenup.api.dto.campfire.CampfireControlMode
import com.calypsan.listenup.api.dto.campfire.CampfireId
import com.calypsan.listenup.api.dto.campfire.OpenCampfireSummary
import com.calypsan.listenup.client.design.components.BookCoverImage
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.campfire_flow_create_eyebrow
import listenup.composeapp.generated.resources.campfire_flow_create_title
import listenup.composeapp.generated.resources.campfire_flow_everyone_controls_sub
import listenup.composeapp.generated.resources.campfire_flow_everyone_controls_title
import listenup.composeapp.generated.resources.campfire_flow_light_the_fire
import listenup.composeapp.generated.resources.campfire_flow_listening_to
import listenup.composeapp.generated.resources.campfire_flow_name_label
import listenup.composeapp.generated.resources.campfire_flow_privacy_anyone
import listenup.composeapp.generated.resources.campfire_flow_who_can_join
import listenup.composeapp.generated.resources.campfire_invite_only
import listenup.composeapp.generated.resources.campfire_join
import listenup.composeapp.generated.resources.campfire_listening_now
import listenup.composeapp.generated.resources.campfire_listening_now_count
import org.jetbrains.compose.resources.stringResource

/**
 * Draft settings accumulated by [CampfireCreateScreen] before the flow moves on to
 * [CampfireInviteScreen] — the split mirrors [com.calypsan.listenup.api.dto.campfire.CampfireSettings]
 * minus `invitedUserIds`, which the Invite screen supplies.
 */
internal data class CampfireCreateDraft(
    val name: String,
    val controlMode: CampfireControlMode,
    val inviteOnly: Boolean,
)

/**
 * Builds the "{Host}'s Campfire" default name IN CODE — never via a localized template (#1079:
 * apostrophes break the string-escaping pipeline).
 */
private fun defaultCampfireName(hostDisplayName: String?): String {
    val host = hostDisplayName?.takeIf { it.isNotBlank() } ?: "Your"
    return "$host's Campfire"
}

/**
 * Screen 1 of the full-screen Campfire flow (Create → Invite → Lobby → Room, co-listening design
 * spec's 2026-07-11 lobby amendment, task L3) — replaces the Task-10 `CampfireBookSheet`. Renders
 * over an [CampfireBackdropStage.EMBER] [CampfireBackdrop]: the book strip, a name field defaulted
 * to [defaultCampfireName], the privacy toggle, and the control-mode toggle. Also folds in the old
 * sheet's "join an existing open campfire" list (via [liveCampfires]/[onJoin]) so replacing the
 * sheet with this screen doesn't drop that capability.
 *
 * @param onNext Called with the gathered [CampfireCreateDraft] when "Light the fire" is tapped —
 * the flow moves on to [CampfireInviteScreen], which calls back with the invited user ids once the
 * caller is ready to actually create the session (see that screen's KDoc for why creation is
 * deferred to the end of Invite, not here).
 */
@Composable
internal fun CampfireCreateScreen(
    book: CampfireFlowBook,
    hostDisplayName: String?,
    liveCampfires: List<OpenCampfireSummary>,
    onJoin: (CampfireId) -> Unit,
    onBack: () -> Unit,
    onNext: (CampfireCreateDraft) -> Unit,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
) {
    var name by remember(book.bookId) { mutableStateOf(defaultCampfireName(hostDisplayName)) }
    var nameEdited by remember(book.bookId) { mutableStateOf(false) }
    LaunchedEffect(hostDisplayName) {
        if (!nameEdited) name = defaultCampfireName(hostDisplayName)
    }
    var inviteOnly by remember { mutableStateOf(true) }
    var controlMode by remember { mutableStateOf(CampfireControlMode.EVERYONE) }

    CampfireBackdrop(
        stage = CampfireBackdropStage.EMBER,
        modifier = modifier.fillMaxSize(),
        reducedMotion = reducedMotion,
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CampfireFlowHeader(
                    eyebrow = stringResource(Res.string.campfire_flow_create_eyebrow),
                    title = stringResource(Res.string.campfire_flow_create_title),
                    onBack = onBack,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(vertical = 18.dp, horizontal = 16.dp),
            ) {
                item {
                    Column(modifier = Modifier.widthIn(max = CampfireFlowContentMaxWidth).fillMaxWidth()) {
                        CampfireBookStrip(book)

                        if (liveCampfires.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            CampfireSectionLabel(stringResource(Res.string.campfire_listening_now))
                            Spacer(Modifier.height(8.dp))
                            CampfireLiveNowList(liveCampfires, onJoin)
                        }

                        Spacer(Modifier.height(20.dp))
                        CampfireSectionLabel(stringResource(Res.string.campfire_flow_name_label))
                        Spacer(Modifier.height(8.dp))
                        CampfireNameField(name = name, onNameChange = {
                            name = it
                            nameEdited = true
                        })

                        Spacer(Modifier.height(20.dp))
                        CampfireSectionLabel(stringResource(Res.string.campfire_flow_who_can_join))
                        Spacer(Modifier.height(8.dp))
                        CampfireSegmentedControl(
                            options =
                                listOf(
                                    true to stringResource(Res.string.campfire_invite_only),
                                    false to stringResource(Res.string.campfire_flow_privacy_anyone),
                                ),
                            selected = inviteOnly,
                            onSelect = { inviteOnly = it },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Spacer(Modifier.height(16.dp))
                        CampfireGlassCard(modifier = Modifier.fillMaxWidth()) {
                            CampfireToggleRow(
                                title = stringResource(Res.string.campfire_flow_everyone_controls_title),
                                subtitle = stringResource(Res.string.campfire_flow_everyone_controls_sub),
                                checked = controlMode == CampfireControlMode.EVERYONE,
                                onCheckedChange = {
                                    controlMode =
                                        if (it) CampfireControlMode.EVERYONE else CampfireControlMode.HOST_ONLY
                                },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CampfireFireButton(
                    text = stringResource(Res.string.campfire_flow_light_the_fire),
                    onClick = {
                        onNext(
                            CampfireCreateDraft(
                                name = name.ifBlank { defaultCampfireName(hostDisplayName) },
                                controlMode = controlMode,
                                inviteOnly = inviteOnly,
                            ),
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.LocalFireDepartment,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    },
                    modifier =
                        Modifier
                            .widthIn(max = CampfireFlowContentMaxWidth)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun CampfireBookStrip(book: CampfireFlowBook) {
    CampfireGlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BookCoverImage(
                bookId = book.bookId,
                coverPath = book.coverPath,
                coverHash = book.coverHash,
                blurHash = book.coverBlurHash,
                contentDescription = book.title,
                title = book.title,
                modifier = Modifier.size(58.dp).clip(RoundedCornerShape(11.dp)),
            )
            Column {
                Text(
                    text = stringResource(Res.string.campfire_flow_listening_to),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 10.5.sp,
                    letterSpacing = 1.sp,
                )
                Text(
                    text = book.title,
                    color = CampfireFlowColors.OnGlass,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
                if (book.subtitle.isNotBlank()) {
                    Text(text = book.subtitle, color = CampfireFlowColors.OnGlassMuted, fontSize = 12.5.sp)
                }
            }
        }
    }
}

@Composable
private fun CampfireLiveNowList(
    liveCampfires: List<OpenCampfireSummary>,
    onJoin: (CampfireId) -> Unit,
) {
    CampfireGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            liveCampfires.forEach { summary ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(Res.string.campfire_listening_now_count, summary.memberCount),
                        color = CampfireFlowColors.OnGlass,
                        fontSize = 14.sp,
                    )
                    CampfireFireButton(
                        text = stringResource(Res.string.campfire_join),
                        onClick = { onJoin(summary.id) },
                        modifier = Modifier.height(36.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CampfireNameField(
    name: String,
    onNameChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedTextColor = CampfireFlowColors.OnGlass,
                unfocusedTextColor = CampfireFlowColors.OnGlass,
                focusedContainerColor = CampfireFlowColors.Glass.copy(alpha = 0.62f),
                unfocusedContainerColor = CampfireFlowColors.Glass.copy(alpha = 0.62f),
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = CampfireFlowColors.GlassBorder,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
    )
}
