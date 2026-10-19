package com.calypsan.listenup.client.features.discover.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.AvatarSize
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.components.UserAvatar
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.design.util.relativeTime
import com.calypsan.listenup.client.presentation.discover.ActivityParts
import com.calypsan.listenup.client.presentation.discover.ActivityUiModel
import com.calypsan.listenup.client.presentation.discover.activityParts
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_more_actions
import listenup.composeapp.generated.resources.discover_activity_profile_a11y
import listenup.composeapp.generated.resources.discover_activity_you
import listenup.composeapp.generated.resources.discover_activity_your_profile_a11y
import org.jetbrains.compose.resources.stringResource

/**
 * One activity row, shared by the Discover section and the See all activity screen (plan C6): the
 * avatar (its own profile link), then one link holding the sentence, when it happened and, for a
 * book, its cover.
 *
 * Its further actions ([activityRowActions]) open in an M3 menu anchored to the row on a long-press,
 * are TalkBack custom actions, and open from the keyboard's Menu key (or Shift+F10), so none of
 * them needs a long-press. A row with no actions (a join, a milestone) has no menu at all.
 *
 * @param onAddToShelf Shelve the row's one book; null hides that action (a host without a picker).
 */
@Composable
internal fun ActivityRow(
    activity: ActivityUiModel,
    onBookClick: (String) -> Unit,
    onShelfClick: (String) -> Unit,
    onUserClick: (String) -> Unit,
    onAddToShelf: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val actions =
        remember(activity, onAddToShelf != null) {
            activityRowActions(activity).filter { it !is ActivityRowAction.AddToShelf || onAddToShelf != null }
        }
    val perform: (ActivityRowAction) -> Unit = { action ->
        when (action) {
            is ActivityRowAction.OpenBook -> onBookClick(action.bookId)
            is ActivityRowAction.OpenShelf -> onShelfClick(action.shelfId)
            is ActivityRowAction.OpenProfile -> onUserClick(action.userId)
            is ActivityRowAction.AddToShelf -> onAddToShelf?.invoke(action.bookId)
        }
    }
    val bookId = activity.bookId
    val shelfId = activity.shelfId
    // X31: a row with neither book nor shelf opens the person it is about.
    val openTarget: () -> Unit =
        when {
            bookId != null -> {
                { onBookClick(bookId) }
            }

            shelfId != null -> {
                { onShelfClick(shelfId) }
            }

            else -> {
                { onUserClick(activity.userId) }
            }
        }

    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("activity-row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        ActivityAvatarLink(activity = activity, onUserClick = onUserClick)
        Box(Modifier.weight(1f)) {
            var menuOpen by remember { mutableStateOf(false) }
            val labels = actions.map { it.label(activity) }
            ActivityRowBody(
                activity = activity,
                modifier =
                    Modifier.activityRowGestures(
                        labels = labels,
                        actions = actions,
                        onOpen = openTarget,
                        onOpenMenu = { menuOpen = true },
                        perform = perform,
                    ),
            )
            ActivityRowMenu(
                expanded = menuOpen,
                actions = actions,
                labels = labels,
                onDismiss = { menuOpen = false },
                perform = perform,
            )
        }
    }
}

/** The avatar: its own profile link beside the row's body, never inside it. */
@Composable
private fun ActivityAvatarLink(
    activity: ActivityUiModel,
    onUserClick: (String) -> Unit,
) {
    val haptics = LocalHaptics.current
    val profileLabel =
        if (activity.isYou) {
            stringResource(Res.string.discover_activity_your_profile_a11y)
        } else {
            stringResource(Res.string.discover_activity_profile_a11y, activity.userDisplayName)
        }
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = profileLabel) {
                    haptics.press()
                    onUserClick(activity.userId)
                }.semantics { contentDescription = profileLabel },
        contentAlignment = Alignment.Center,
    ) {
        UserAvatar(
            userId = activity.userId,
            size = AvatarSize.Medium,
            fallbackName = activity.userDisplayName,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/**
 * The body's gestures: a tap opens the row's subject; a long-press, or the Menu key (or Shift+F10)
 * while focused, opens the menu; and every action is a TalkBack custom action. With no actions it is
 * a plain link.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.activityRowGestures(
    labels: List<String>,
    actions: List<ActivityRowAction>,
    onOpen: () -> Unit,
    onOpenMenu: () -> Unit,
    perform: (ActivityRowAction) -> Unit,
): Modifier {
    val haptics = LocalHaptics.current
    val hasActions = actions.isNotEmpty()
    val moreActionsLabel = stringResource(Res.string.common_more_actions)
    return this
        .onPreviewKeyEvent { event ->
            val isMenuKey = event.key == Key.Menu || (event.key == Key.F10 && event.isShiftPressed)
            if (isMenuKey && hasActions && event.type == KeyEventType.KeyUp) onOpenMenu()
            isMenuKey && hasActions
        }.combinedClickable(
            // Our gated haptics.longPress() owns the feel; suppress the built-in one.
            hapticFeedbackEnabled = false,
            role = Role.Button,
            onLongClickLabel = moreActionsLabel.takeIf { hasActions },
            onLongClick =
                if (hasActions) {
                    {
                        haptics.longPress()
                        onOpenMenu()
                    }
                } else {
                    null
                },
            onClick = {
                haptics.press()
                onOpen()
            },
        ).semantics(mergeDescendants = true) {
            if (hasActions) {
                customActions =
                    actions.mapIndexed { index, action ->
                        CustomAccessibilityAction(labels[index]) {
                            perform(action)
                            true
                        }
                    }
            }
        }
}

/** The body: the sentence and when, then a book's cover. */
@Composable
private fun ActivityRowBody(
    activity: ActivityUiModel,
    modifier: Modifier,
) {
    val parts = remember(activity) { activityParts(activity) }
    val actor = if (activity.isYou) stringResource(Res.string.discover_activity_you) else activity.userDisplayName
    val bodyColor = MaterialTheme.colorScheme.onSurface
    val highlightColor = MaterialTheme.colorScheme.primary
    val line =
        remember(actor, parts, bodyColor, highlightColor) {
            activityLine(actor = actor, parts = parts, bodyColor = bodyColor, highlightColor = highlightColor)
        }
    Row(
        // Inset from the rounded clip, so the corners round the ripple and never a letter of the text.
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(MaterialTheme.shapes.small)
                .then(modifier)
                .padding(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = relativeTime(activity.occurredAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        activity.bookId?.let { bookId ->
            BookCoverImage(
                bookId = bookId,
                coverPath = activity.bookCoverPath,
                contentDescription = null,
                coverHash = activity.bookCoverHash,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).testTag("activity-cover"),
            )
        }
    }
}

/** The M3 menu, anchored to the row's body. */
@Composable
private fun ActivityRowMenu(
    expanded: Boolean,
    actions: List<ActivityRowAction>,
    labels: List<String>,
    onDismiss: () -> Unit,
    perform: (ActivityRowAction) -> Unit,
) {
    val haptics = LocalHaptics.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        actions.forEachIndexed { index, action ->
            DropdownMenuItem(
                text = { Text(labels[index]) },
                leadingIcon = { Icon(imageVector = action.icon, contentDescription = null) },
                onClick = {
                    haptics.press()
                    onDismiss()
                    perform(action)
                },
            )
        }
    }
}

/** Build the activity sentence: bold actor, plain predicate, brand-coloured highlight, plain suffix. */
private fun activityLine(
    actor: String,
    parts: ActivityParts,
    bodyColor: Color,
    highlightColor: Color,
): AnnotatedString =
    buildAnnotatedString {
        withStyle(SpanStyle(color = bodyColor, fontWeight = FontWeight.Bold)) {
            append(actor)
        }
        withStyle(SpanStyle(color = bodyColor)) {
            append(" ${parts.predicate}")
        }
        parts.highlight?.let { highlight ->
            append(" ")
            withStyle(SpanStyle(color = highlightColor, fontWeight = FontWeight.Bold)) {
                append(highlight)
            }
        }
        if (parts.suffix.isNotEmpty()) {
            withStyle(SpanStyle(color = bodyColor)) {
                append(parts.suffix)
            }
        }
    }
