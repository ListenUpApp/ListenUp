package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.CountBadge
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_chapters
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import listenup.composeapp.generated.resources.book_detail_chapter_count
import listenup.composeapp.generated.resources.book_detail_chapter_count_one

/**
 * Chapter list header showing the section title, a [CountBadge] with the total count, and an
 * optional trailing sort affordance slot.
 *
 * @param chapterCount Total number of chapters — displayed inside the count badge.
 * @param trailingContent Optional composable placed at the end of the header row (e.g. sort icon).
 */
@Composable
fun ChaptersHeader(
    chapterCount: Int,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val countDescription =
        if (chapterCount == 1) {
            stringResource(Res.string.book_detail_chapter_count_one)
        } else {
            stringResource(Res.string.book_detail_chapter_count, chapterCount)
        }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The title and its count are one heading: "Chapters, 19 chapters". The trailing control keeps its own node.
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) { heading() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.book_detail_chapters),
                // Medium: the hero title is the screen's one display moment (rev 7 type rule).
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
            )

            Spacer(modifier = Modifier.width(8.dp))

            CountBadge(count = chapterCount, contentDescription = countDescription)
        }

        Spacer(modifier = Modifier.weight(1f))

        trailingContent?.invoke()
    }
}
