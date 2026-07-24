package com.calypsan.listenup.client.features.contributors

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.domain.model.BookContributor
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_authors
import listenup.composeapp.generated.resources.book_detail_cast_count_authors
import listenup.composeapp.generated.resources.book_detail_cast_count_narrators
import listenup.composeapp.generated.resources.book_detail_done
import listenup.composeapp.generated.resources.book_detail_full_cast
import org.jetbrains.compose.resources.stringResource

/** Which contributor role a [FullCastSheet] is showing — selected by a folded hero line. */
enum class CastRole { Authors, Narrators }

/**
 * Opens the [FullCastSheet] for the given [role], resolving the title and count line from string
 * resources. Centralises the wiring so the compact and wide layouts both just track a nullable
 * [CastRole] and render this when it is set.
 *
 * @param authors      The book's authors (shown when [role] is [CastRole.Authors]).
 * @param narrators    The book's narrators (shown when [role] is [CastRole.Narrators]).
 */
@Composable
fun FullCastSheetFor(
    role: CastRole,
    authors: List<BookContributor>,
    narrators: List<BookContributor>,
    onContributorClick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val isNarrators = role == CastRole.Narrators
    val contributors = if (isNarrators) narrators else authors
    val title = stringResource(if (isNarrators) Res.string.book_detail_full_cast else Res.string.book_detail_authors)
    val countText =
        stringResource(
            if (isNarrators) Res.string.book_detail_cast_count_narrators else Res.string.book_detail_cast_count_authors,
            contributors.size,
        )
    FullCastSheet(
        title = title,
        countText = countText,
        contributors = contributors,
        onContributorClick = onContributorClick,
        onDismiss = onDismiss,
    )
}

/**
 * The full roster overlay opened from a folded hero contributor line (e.g. "Roy Dotrice, 13 other
 * narrators"). Lists every contributor in [contributors] individually with an initials avatar, each
 * tappable to its detail page. No contributor is singled out as a "lead" — co-narrated books and
 * anthologies have no canonical primary, so every row reads equally.
 *
 * Adapts to width: a bottom sheet on compact/medium screens, a centred dialog at expanded width —
 * mirroring the Book Detail screen's own layout split.
 *
 * @param title         Overlay heading (e.g. "Full cast", "Authors").
 * @param countText     Supporting line under the title (e.g. "14 narrators").
 * @param contributors  The full roster; the caller guarantees a non-empty list.
 * @param onContributorClick Invoked with a contributor id when a row is tapped (also dismisses).
 * @param onDismiss     Invoked when the overlay is dismissed without a selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullCastSheet(
    title: String,
    countText: String,
    contributors: List<BookContributor>,
    onContributorClick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val expanded =
        currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
        )

    val onSelect: (String) -> Unit = { id ->
        onContributorClick(id)
        onDismiss()
    }

    if (expanded) {
        FullCastDialog(
            title = title,
            countText = countText,
            contributors = contributors,
            onSelect = onSelect,
            onDismiss = onDismiss,
        )
    } else {
        FullCastBottomSheet(
            title = title,
            countText = countText,
            contributors = contributors,
            onSelect = onSelect,
            onDismiss = onDismiss,
        )
    }
}

/** Compact / medium: a Material bottom sheet with a drag handle and a scrollable roster. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullCastBottomSheet(
    title: String,
    countText: String,
    contributors: List<BookContributor>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Surface(
                modifier =
                    Modifier
                        .padding(vertical = 12.dp)
                        .size(width = 32.dp, height = 4.dp),
                shape = RoundedCornerShape(2.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            ) {}
        },
    ) {
        CastHeader(
            title = title,
            countText = countText,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            itemsIndexed(items = contributors, key = { _, c -> c.id }) { index, contributor ->
                CastRow(
                    name = contributor.name,
                    index = index,
                    onClick = { onSelect(contributor.id) },
                )
            }
        }
    }
}

/** Expanded: a centred dialog with a scrollable two-column roster and a Done action. */
@Composable
private fun FullCastDialog(
    title: String,
    countText: String,
    contributors: List<BookContributor>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(top = 26.dp, bottom = 12.dp)) {
                CastHeader(
                    title = title,
                    countText = countText,
                    modifier = Modifier.padding(horizontal = 28.dp),
                )
                Column(
                    modifier =
                        Modifier
                            .heightIn(max = 440.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    contributors.chunked(2).forEachIndexed { rowIndex, pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEachIndexed { colIndex, contributor ->
                                CastRow(
                                    name = contributor.name,
                                    index = rowIndex * 2 + colIndex,
                                    onClick = { onSelect(contributor.id) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(text = stringResource(Res.string.book_detail_done))
                    }
                }
            }
        }
    }
}

/** Shared title + supporting count line for both overlay variants. */
@Composable
private fun CastHeader(
    title: String,
    countText: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style =
                MaterialTheme.typography.headlineSmall.copy(
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.Bold,
                ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = countText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One roster row: an initials avatar and the contributor's name. Every row reads equally. */
@Composable
private fun CastRow(
    name: String,
    index: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CastAvatar(name = name, index = index)
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Initials-circle avatar; the background tone cycles through the container palette by [index]. */
@Composable
private fun CastAvatar(
    name: String,
    index: Int,
    size: Dp = 44.dp,
) {
    val (background, foreground) =
        when (index % 3) {
            1 -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
            2 -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
            else -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        }
    Box(
        modifier = Modifier.size(size).background(background, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = castInitials(name),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = foreground,
        )
    }
}

/** First + last word initials of [name], uppercased; "?" when [name] yields nothing. */
private fun castInitials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (parts.isEmpty()) return "?"
    val first = parts.first().first()
    val last = if (parts.size > 1) parts.last().first() else ""
    return "$first$last".uppercase()
}
