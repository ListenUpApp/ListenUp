package com.calypsan.listenup.client.features.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.api.notifications.NotificationEvent
import com.calypsan.listenup.client.data.repository.ShortcutAction
import com.calypsan.listenup.client.design.components.ContentRow
import com.calypsan.listenup.client.design.components.EmptyState
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.design.components.TonalIconTile
import com.calypsan.listenup.client.design.util.relativeTime
import com.calypsan.listenup.client.domain.model.AppNotification
import com.calypsan.listenup.client.presentation.notifications.NotificationsUiState
import com.calypsan.listenup.client.presentation.notifications.NotificationsViewModel
import com.calypsan.listenup.client.presentation.notifications.toShortcutAction
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.notifications_empty_subtitle
import listenup.composeapp.generated.resources.notifications_empty_title
import listenup.composeapp.generated.resources.notifications_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** Max readable content width — wide windows centre the list rather than stretch it. */
private val ContentMaxWidth = 640.dp

/** Diameter of the unread indicator dot leading an unread row's title. */
private val UnreadDotSize = 8.dp

/**
 * The notification inbox. Tap = mark read + navigate via [toShortcutAction] — the SAME mapping the
 * system shade uses, so the two entry points cannot disagree. Unknown types render generic copy.
 *
 * @param onNavigateBack Navigate back to the shell.
 * @param onAction Dispatches the tapped notification's [ShortcutAction] into navigation.
 * @param modifier Modifier for the screen scaffold.
 * @param viewModel The inbox ViewModel, provided via Koin.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    onNavigateBack: () -> Unit,
    onAction: (ShortcutAction) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NotificationsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    ListenUpScaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.notifications_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.common_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        when (val s = state) {
            is NotificationsUiState.Loading -> {
                FullScreenLoadingIndicator(modifier = Modifier.padding(padding))
            }

            is NotificationsUiState.Empty -> {
                EmptyState(
                    icon = Icons.Outlined.NotificationsNone,
                    title = stringResource(Res.string.notifications_empty_title),
                    subtitle = stringResource(Res.string.notifications_empty_subtitle),
                    modifier = Modifier.padding(padding),
                )
            }

            is NotificationsUiState.Data -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LazyColumn(
                        modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(s.notifications, key = { it.id }) { notification ->
                            NotificationRow(
                                notification = notification,
                                onClick = {
                                    viewModel.markRead(notification.id)
                                    notification.toShortcutAction()?.let(onAction)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One inbox row: the type's tonal icon tile, the resolved title/body copy, a trailing relative
 * timestamp, and — while unread — an emphasized title led by a small primary dot. Read rows drop
 * the dot and mute the title. The timestamp goes through the shared [relativeTime] util, so an
 * inbox row and an activity-feed row phrase the same age identically.
 */
@Composable
private fun NotificationRow(
    notification: AppNotification,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (titleRes, bodyRes) = notificationItemCopyRes(notification.event)
    ContentRow(
        onClick = onClick,
        modifier = modifier,
    ) {
        TonalIconTile(icon = notificationIcon(notification.event))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (notification.isUnread) {
                    Box(
                        modifier =
                            Modifier
                                .size(UnreadDotSize)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (notification.isUnread) FontWeight.Bold else null,
                    color =
                        if (notification.isUnread) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
            Text(
                text = stringResource(bodyRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = relativeTime(notification.createdAt),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The leading tile glyph for a notification [event]; unknown types get the generic bell. */
private fun notificationIcon(event: NotificationEvent?): ImageVector =
    when (event) {
        is NotificationEvent.CampfireInvite -> Icons.Outlined.LocalFireDepartment
        is NotificationEvent.RegistrationDecision -> Icons.Outlined.HowToReg
        is NotificationEvent.RegistrationApproval -> Icons.Outlined.PersonAdd
        null -> Icons.Outlined.Notifications
    }
