package com.calypsan.listenup.client.features.discover.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
import com.calypsan.listenup.client.design.components.InlineRetry
import com.calypsan.listenup.client.design.components.RowsSkeleton
import com.calypsan.listenup.client.design.components.SectionTitle
import com.calypsan.listenup.client.design.components.UserAvatar
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.design.util.relativeTime
import com.calypsan.listenup.client.presentation.discover.ActivityFeedUiState
import com.calypsan.listenup.client.presentation.discover.ActivityParts
import com.calypsan.listenup.client.presentation.discover.ActivityUiModel
import com.calypsan.listenup.client.presentation.discover.activityParts
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.discover_activity
import listenup.composeapp.generated.resources.discover_activity_profile_a11y
import listenup.composeapp.generated.resources.discover_error_activity
import listenup.composeapp.generated.resources.discover_activity_show_less
import listenup.composeapp.generated.resources.discover_activity_you
import listenup.composeapp.generated.resources.discover_activity_your_profile_a11y
import listenup.composeapp.generated.resources.discover_no_activity_yet_start_listening
import listenup.composeapp.generated.resources.discover_see_all_activity
import org.jetbrains.compose.resources.stringResource

/** Rows shown before "See all activity". */
private const val ACTIVITY_CAP = 6

/**
 * Discover's "Activity": what people on the server have been doing, newest first, in the one shared
 * sentence ([activityParts]). Your own rows read "You". Every row leads somewhere: a book row opens
 * the book, a shelf row the shelf, and a row with neither (a join, a milestone) the person. The
 * avatar is its own profile link beside the row's.
 *
 * Six rows show, then "See all activity". Until PR 6's See all screen exists the link expands the
 * section in place to the full feed and then reads "Show less"; PR 6 swaps this for navigation (plan Q1).
 *
 * Loading is a skeleton of rows and a failure a line with its own Try again (spec §3.8).
 */
@Composable
internal fun ActivityFeedSection(
    state: ActivityFeedUiState,
    onBookClick: (String) -> Unit,
    onShelfClick: (String) -> Unit,
    onUserClick: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionTitle(title = stringResource(Res.string.discover_activity))

        when (val s = state) {
            is ActivityFeedUiState.Loading -> {
                RowsSkeleton(rows = ACTIVITY_CAP, modifier = Modifier.testTag("activity-skeleton"))
            }

            is ActivityFeedUiState.Error -> {
                InlineRetry(line = stringResource(Res.string.discover_error_activity), onRetry = onRetry)
            }

            is ActivityFeedUiState.Ready -> {
                if (s.isEmpty) {
                    Text(
                        text = stringResource(Res.string.discover_no_activity_yet_start_listening),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ActivityRows(
                        activities = s.activities,
                        onBookClick = onBookClick,
                        onShelfClick = onShelfClick,
                        onUserClick = onUserClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityRows(
    activities: List<ActivityUiModel>,
    onBookClick: (String) -> Unit,
    onShelfClick: (String) -> Unit,
    onUserClick: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val visible = if (expanded) activities else activities.take(ACTIVITY_CAP)
    visible.forEachIndexed { index, activity ->
        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ActivityItem(
            activity = activity,
            onBookClick = onBookClick,
            onShelfClick = onShelfClick,
            onUserClick = onUserClick,
        )
    }
    if (activities.size > ACTIVITY_CAP) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Text(
                text =
                    if (expanded) {
                        stringResource(Res.string.discover_activity_show_less)
                    } else {
                        stringResource(Res.string.discover_see_all_activity)
                    },
            )
        }
    }
}

/**
 * One row: the avatar (its own profile link), then one link holding the sentence, when it happened
 * and, for a book, its cover.
 */
@Composable
private fun ActivityItem(
    activity: ActivityUiModel,
    onBookClick: (String) -> Unit,
    onShelfClick: (String) -> Unit,
    onUserClick: (String) -> Unit,
) {
    val haptics = LocalHaptics.current
    val parts = remember(activity) { activityParts(activity) }
    val actor = if (activity.isYou) stringResource(Res.string.discover_activity_you) else activity.userDisplayName
    val profileLabel =
        if (activity.isYou) {
            stringResource(Res.string.discover_activity_your_profile_a11y)
        } else {
            stringResource(Res.string.discover_activity_profile_a11y, activity.userDisplayName)
        }

    val bodyColor = MaterialTheme.colorScheme.onSurface
    val highlightColor = MaterialTheme.colorScheme.primary
    val line =
        remember(actor, parts, bodyColor, highlightColor) {
            activityLine(actor = actor, parts = parts, bodyColor = bodyColor, highlightColor = highlightColor)
        }

    val bookId = activity.bookId
    val shelfId = activity.shelfId
    val openTarget: () -> Unit =
        when {
            bookId != null -> {
                { onBookClick(bookId) }
            }

            shelfId != null -> {
                { onShelfClick(shelfId) }
            }

            // X31: a row with neither book nor shelf opens the person it is about.
            else -> {
                { onUserClick(activity.userId) }
            }
        }

    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
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
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(role = Role.Button) {
                        haptics.press()
                        openTarget()
                    }.semantics(mergeDescendants = true) {},
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
            if (bookId != null) {
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
