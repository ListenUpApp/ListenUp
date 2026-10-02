package com.calypsan.listenup.client.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.dto.hardcover.HardcoverBrokenReason
import com.calypsan.listenup.api.dto.hardcover.HardcoverLinkFailure
import com.calypsan.listenup.api.dto.hardcover.HardcoverShareMode
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.SectionSegment
import com.calypsan.listenup.client.design.components.SegmentedGroup
import com.calypsan.listenup.client.design.components.avatarInitials
import com.calypsan.listenup.client.design.components.cookieScallopShape
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.ContentShapes
import com.calypsan.listenup.client.design.theme.HeroInk
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.design.util.rememberCopyToClipboard
import com.calypsan.listenup.client.presentation.settings.HardcoverSettingsUiState
import com.calypsan.listenup.client.util.formatDateLong
import kotlinx.coroutines.launch
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.hardcover_broken_cannot_decrypt
import listenup.composeapp.generated.resources.hardcover_broken_missing_scope
import listenup.composeapp.generated.resources.hardcover_broken_revoked
import listenup.composeapp.generated.resources.hardcover_broken_title
import listenup.composeapp.generated.resources.hardcover_cancel_linking
import listenup.composeapp.generated.resources.hardcover_code_copied
import listenup.composeapp.generated.resources.hardcover_connect
import listenup.composeapp.generated.resources.hardcover_connected
import listenup.composeapp.generated.resources.hardcover_connected_since
import listenup.composeapp.generated.resources.hardcover_copy_code
import listenup.composeapp.generated.resources.hardcover_disconnect
import listenup.composeapp.generated.resources.hardcover_failure_denied
import listenup.composeapp.generated.resources.hardcover_failure_expired
import listenup.composeapp.generated.resources.hardcover_failure_unreachable
import listenup.composeapp.generated.resources.hardcover_intro
import listenup.composeapp.generated.resources.hardcover_linking_instructions
import listenup.composeapp.generated.resources.hardcover_linking_title
import listenup.composeapp.generated.resources.hardcover_not_connected_title
import listenup.composeapp.generated.resources.hardcover_open_hardcover
import listenup.composeapp.generated.resources.hardcover_reconnect
import listenup.composeapp.generated.resources.hardcover_shares_finished
import listenup.composeapp.generated.resources.hardcover_shares_no_password
import listenup.composeapp.generated.resources.hardcover_waiting_detail
import listenup.composeapp.generated.resources.hardcover_waiting_title
import listenup.composeapp.generated.resources.hardcover_was_connected_as
import listenup.composeapp.generated.resources.hardcover_your_code
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private val HeroGlyphSize = 56.dp
private val ConnectedAvatarSize = 64.dp
private val WaitingIndicatorSize = 32.dp

/** The regions of every phase that has a connection to show (all but Loading and NotOffered). */
internal fun hardcoverPhase(
    state: HardcoverSettingsUiState,
    isWide: Boolean,
    onConnect: () -> Unit,
    onOpenHardcover: () -> Unit,
    onCancelLinking: () -> Unit,
    onRequestDisconnect: () -> Unit,
    onSyncNow: () -> Unit,
    onSetShareMode: (HardcoverShareMode) -> Unit,
    onSendHistory: () -> Unit,
    onDismissHistory: () -> Unit,
    onFindMatch: (bookId: String) -> Unit,
    onOpenKeptOff: () -> Unit,
): HardcoverPhase =
    when (state) {
        is HardcoverSettingsUiState.NotConnected -> {
            HardcoverPhase(
                lead = { NotConnectedHero(lastFailure = state.lastFailure, bleeds = !isWide) },
                detail = { WhatConnectingMeans() },
                actions = {
                    ListenUpButton(
                        text = stringResource(Res.string.hardcover_connect),
                        onClick = onConnect,
                        isLoading = state.isStarting,
                    )
                },
                leadIsBleedingHero = !isWide,
            )
        }

        is HardcoverSettingsUiState.Linking -> {
            HardcoverPhase(
                lead = { LinkingCode(state) },
                detail = { WaitingStatus() },
                actions = {
                    ListenUpButton(
                        text = stringResource(Res.string.hardcover_open_hardcover),
                        onClick = onOpenHardcover,
                        trailingIcon = Icons.AutoMirrored.Outlined.OpenInNew,
                    )
                    QuietButton(
                        text = stringResource(Res.string.hardcover_cancel_linking),
                        onClick = onCancelLinking,
                    )
                },
            )
        }

        is HardcoverSettingsUiState.Connected -> {
            HardcoverPhase(
                lead = { ConnectedHero(username = state.username, since = state.since, bleeds = !isWide) },
                // Canvas order: the earlier-books card, sync, then Needs a match (the one part that asks
                // you to act), then sharing.
                detail = {
                    val needsMatch = remember { BringIntoViewRequester() }
                    val scope = rememberCoroutineScope()
                    HardcoverHistoryCard(
                        history = state.history,
                        onSend = onSendHistory,
                        onDismiss = onDismissHistory,
                        onShowNeedsMatch = { scope.launch { needsMatch.bringIntoView() } },
                    )
                    HardcoverSyncBlock(
                        lastSyncedAt = state.lastSyncedAt,
                        sync = state.sync,
                        history = state.history,
                        keptOffBookCount = state.keptOffBookCount,
                        onSyncNow = onSyncNow,
                        onSendHistory = onSendHistory,
                        onOpenKeptOff = onOpenKeptOff,
                    )
                    HardcoverNeedsMatch(
                        books = state.booksToMatch,
                        isKnown = state.isMatchListKnown,
                        onFindMatch = onFindMatch,
                        modifier = Modifier.bringIntoViewRequester(needsMatch),
                    )
                    HardcoverWhatIsShared(
                        shareMode = state.shareMode,
                        isSavingShareMode = state.isSavingShareMode,
                        onSetShareMode = onSetShareMode,
                    )
                },
                actions = {
                    ListenUpButton(
                        text = stringResource(Res.string.hardcover_disconnect),
                        onClick = onRequestDisconnect,
                        isLoading = state.isDisconnecting,
                        filled = false,
                        danger = true,
                    )
                },
                leadIsBleedingHero = !isWide,
            )
        }

        is HardcoverSettingsUiState.Broken -> {
            HardcoverPhase(
                lead = { BrokenExplanation(reason = state.reason, username = state.username) },
                detail = null,
                actions = {
                    ListenUpButton(
                        text = stringResource(Res.string.hardcover_reconnect),
                        onClick = onConnect,
                        isLoading = state.isStarting,
                    )
                    QuietButton(
                        text = stringResource(Res.string.hardcover_disconnect),
                        onClick = onRequestDisconnect,
                        danger = true,
                    )
                },
            )
        }

        HardcoverSettingsUiState.Loading, HardcoverSettingsUiState.NotOffered -> {
            error("$state has no connection phase; HardcoverSettingsContent renders it directly")
        }
    }

// ─────────────────────────── Not connected ───────────────────────────

@Composable
private fun NotConnectedHero(
    lastFailure: HardcoverLinkFailure?,
    bleeds: Boolean,
) {
    HeroBand(bleeds = bleeds) {
        HeroGlyph(icon = Icons.AutoMirrored.Outlined.MenuBook)
        Text(
            text = stringResource(Res.string.hardcover_not_connected_title),
            style = MaterialTheme.typography.headlineMediumEmphasized,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(Res.string.hardcover_intro),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    lastFailure?.let { failure ->
        Notice(
            text = stringResource(failure.messageRes()),
            modifier = if (bleeds) Modifier.padding(horizontal = Spacing.screenMargin) else Modifier,
        )
    }
}

/** What connecting promises, as the design system's segmented list: one segment per promise. */
@Composable
private fun WhatConnectingMeans() {
    SegmentedGroup {
        Statement(icon = Icons.Outlined.Check, text = stringResource(Res.string.hardcover_shares_finished))
        Statement(icon = Icons.Outlined.Lock, text = stringResource(Res.string.hardcover_shares_no_password))
    }
}

/** One promise about what connecting does: a primary glyph beside a sentence, as one segment. */
@Composable
private fun Statement(
    icon: ImageVector,
    text: String,
) {
    SectionSegment {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(text = text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

// ─────────────────────────────── Linking ─────────────────────────────

@Composable
private fun LinkingCode(state: HardcoverSettingsUiState.Linking) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.titleGap)) {
        Text(
            text = stringResource(Res.string.hardcover_linking_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        LinkingInstructions(address = state.verificationUri.withoutScheme())
    }
    CodeCard(code = state.userCode)
}

/** The instructions, with the address to type set in the ink colour so it reads as the thing to do. */
@Composable
private fun LinkingInstructions(address: String) {
    val sentence = stringResource(Res.string.hardcover_linking_instructions, address)
    val addressStyle = SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
    val text =
        buildAnnotatedString {
            val start = sentence.indexOf(address)
            if (start < 0) {
                append(sentence)
            } else {
                append(sentence.substring(0, start))
                withStyle(addressStyle) { append(address) }
                append(sentence.substring(start + address.length))
            }
        }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CodeCard(code: String) {
    val copyToClipboard = rememberCopyToClipboard()
    val haptics = LocalHaptics.current
    var copied by rememberSaveable(code) { mutableStateOf(false) }
    val codeStyle = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace)
    CoralCard(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(Res.string.hardcover_your_code),
            style = MaterialTheme.typography.labelLarge,
        )
        // Sized to fit: a two-column tablet layout can hand the code a narrower card than a phone.
        Text(
            text = code,
            style = codeStyle,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            autoSize =
                TextAutoSize.StepBased(
                    minFontSize = MaterialTheme.typography.titleLarge.fontSize,
                    maxFontSize = codeStyle.fontSize,
                ),
        )
        FilledTonalButton(
            onClick = {
                haptics.press()
                copyToClipboard(code)
                copied = true
            },
            colors =
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = HeroInk.wash(),
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            // Announce the change of label, so a TalkBack user hears that the copy worked.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Icon(
                imageVector = if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Text(
                text = stringResource(if (copied) Res.string.hardcover_code_copied else Res.string.hardcover_copy_code),
                modifier = Modifier.padding(start = ButtonDefaults.IconSpacing),
            )
        }
    }
}

/**
 * The quiet "we're waiting" line. A live region, so a TalkBack user hears it on arrival and isn't
 * left wondering whether the screen is still listening.
 */
@Composable
private fun WaitingStatus() {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ListenUpLoadingIndicator(size = WaitingIndicatorSize)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(Res.string.hardcover_waiting_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(Res.string.hardcover_waiting_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ────────────────────────────── Connected ────────────────────────────

@Composable
private fun ConnectedHero(
    username: String,
    since: Long,
    bleeds: Boolean,
) {
    HeroBand(bleeds = bleeds) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(ConnectedAvatarSize)
                        .background(MaterialTheme.colorScheme.primary, cookieScallopShape()),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = avatarInitials(username),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            // One node for TalkBack: "Connected, simon, Since 26 September 2026".
            Column(
                modifier = Modifier.semantics(mergeDescendants = true) { heading() },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Text(
                        text = stringResource(Res.string.hardcover_connected),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Text(
                    text = username,
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                )
                Text(
                    text = stringResource(Res.string.hardcover_connected_since, formatDateLong(since)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

// ─────────────────────────────── Broken ──────────────────────────────

@Composable
private fun BrokenExplanation(
    reason: HardcoverBrokenReason,
    username: String?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.screenMargin),
            verticalArrangement = Arrangement.spacedBy(Spacing.itemGap),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(HeroGlyphSize)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.SyncProblem,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = stringResource(Res.string.hardcover_broken_title),
                style = MaterialTheme.typography.headlineSmallEmphasized,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(reason.messageRes()),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (username != null) {
                Text(
                    text = stringResource(Res.string.hardcover_was_connected_as, username),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ─────────────────────────────── Shared ──────────────────────────────

/**
 * The coral hero the Not connected and Connected phases lead with. When it [bleeds] (a phone), it is
 * a color-block hero band: full width, running on from the bar in the same colour, and ending in the
 * one hero edge, [ContentShapes.hero]. Otherwise (a tablet column) it is a [CoralCard] panel.
 */
@Composable
private fun HeroBand(
    bleeds: Boolean,
    content: @Composable () -> Unit,
) {
    if (!bleeds) {
        CoralCard(content = content)
        return
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ContentShapes.hero,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(
            modifier =
                Modifier
                    .padding(horizontal = Spacing.screenMargin)
                    .padding(top = Spacing.sm, bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            content()
        }
    }
}

/** A coral panel within the page margins: the code on Linking, and the hero in a tablet column. */
@Composable
private fun CoralCard(
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.screenMargin),
            horizontalAlignment = horizontalAlignment,
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            content()
        }
    }
}

@Composable
private fun HeroGlyph(icon: ImageVector) {
    Box(
        modifier =
            Modifier
                .size(HeroGlyphSize)
                .background(
                    HeroInk.wash(),
                    CircleShape,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null)
    }
}

/** Why the last attempt ended — informational, not alarming: the user can simply connect again. */
@Composable
private fun Notice(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** A secondary action under the primary one: Cancel, or the error-coloured Disconnect. */
@Composable
private fun QuietButton(
    text: String,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    val haptics = LocalHaptics.current
    TextButton(
        onClick = {
            haptics.press()
            onClick()
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        colors =
            if (danger) {
                ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.textButtonColors()
            },
    ) {
        Text(text = text, style = MaterialTheme.typography.titleMedium)
    }
}

private fun String.withoutScheme(): String = substringAfter("://")

private fun HardcoverLinkFailure.messageRes(): StringResource =
    when (this) {
        HardcoverLinkFailure.DENIED -> Res.string.hardcover_failure_denied
        HardcoverLinkFailure.EXPIRED -> Res.string.hardcover_failure_expired
        HardcoverLinkFailure.UNREACHABLE -> Res.string.hardcover_failure_unreachable
    }

private fun HardcoverBrokenReason.messageRes(): StringResource =
    when (this) {
        HardcoverBrokenReason.REVOKED -> Res.string.hardcover_broken_revoked
        HardcoverBrokenReason.CANNOT_DECRYPT -> Res.string.hardcover_broken_cannot_decrypt
        HardcoverBrokenReason.MISSING_SCOPE -> Res.string.hardcover_broken_missing_scope
    }
