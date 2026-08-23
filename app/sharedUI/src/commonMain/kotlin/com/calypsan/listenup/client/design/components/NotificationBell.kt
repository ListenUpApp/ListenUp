package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.notifications_title
import listenup.composeapp.generated.resources.notifications_unread_count_a11y
import org.jetbrains.compose.resources.stringResource

/** Counts above this render as "99+" so the badge never stretches unbounded. */
private const val BADGE_MAX_COUNT = 99

/** Compact pill height for the bell's overlaid unread badge. */
private val BADGE_MIN_SIZE = 18.dp

/**
 * The shell notification bell: an icon button with the house [CountBadge] pill overlaid top-end.
 * The badge hides at zero and the bell fills in while anything is unread; the count caps at "99+"
 * so the badge never stretches.
 *
 * @param unreadCount Live unread notification count; zero hides the badge.
 * @param onClick Invoked when the bell is tapped — opens the notification inbox.
 * @param modifier Modifier for the bell's containing box.
 */
@Composable
fun NotificationBell(
    unreadCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    Box(modifier = modifier) {
        // The badge is a sibling of the button, so a screen reader focusing the button would
        // otherwise announce "Notifications" identically at zero and at ninety-nine. The count
        // rides as a state description — re-announced when it changes — and uses the TRUE count,
        // not the badge's "99+" cap: that cap exists so the pill can't stretch, which is a visual
        // concern a screen reader doesn't share.
        val unreadAnnouncement =
            if (unreadCount > 0) {
                stringResource(Res.string.notifications_unread_count_a11y, unreadCount)
            } else {
                null
            }
        IconButton(
            onClick = {
                haptics.press()
                onClick()
            },
            modifier =
                Modifier.semantics {
                    unreadAnnouncement?.let { stateDescription = it }
                },
        ) {
            Icon(
                imageVector =
                    if (unreadCount > 0) Icons.Filled.Notifications else Icons.Outlined.Notifications,
                contentDescription = stringResource(Res.string.notifications_title),
            )
        }
        if (unreadCount > 0) {
            CountBadge(
                count = unreadCount,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                minSize = BADGE_MIN_SIZE,
                maxCount = BADGE_MAX_COUNT,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 2.dp),
            )
        }
    }
}
