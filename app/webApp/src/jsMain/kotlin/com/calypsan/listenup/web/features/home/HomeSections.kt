package com.calypsan.listenup.web.features.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.LinkButton
import com.calypsan.listenup.web.design.ProgressBar
import com.calypsan.listenup.web.design.ProgressLook
import com.calypsan.listenup.web.design.RestrictedMarker
import com.calypsan.listenup.web.design.coverUrl
import com.calypsan.listenup.web.features.books.BookSelection
import com.calypsan.listenup.web.features.books.press
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import kotlin.time.Duration.Companion.milliseconds

/** Spec §2.5 caps: desktop one row of up to six; the phone CSS shows the first four (C10). */
internal const val ALSO_IN_PROGRESS_CAP = 6

/** The cover rung a card asks the server for, in px. */
private const val CARD_COVER_RUNG = 168

private const val PERCENT = 100

/** "Also in progress" with its header link. Absent when there is nothing else in progress (§2.1). */
@Composable
internal fun AlsoInProgress(
    items: List<ContinueListeningItem>,
    isWide: Boolean,
    actions: HomeActions,
    selection: BookSelection?,
) {
    Section(attrs = {
        classes("home-section", "home-also")
        attr("aria-labelledby", "home-also-h")
    }) {
        Div(attrs = { classes("home-section-row") }) {
            H2(attrs = {
                id("home-also-h")
                classes("home-section-h", "home-also-h")
            }) { Text("Also in progress") }
            // PR 4 adds Library's In progress filter (X6); until then the link opens the Library.
            LinkButton(label = "In Library →", onClick = actions.onOpenLibrary)
        }
        Div(attrs = { classes("home-continue") }) {
            items.take(ALSO_IN_PROGRESS_CAP).forEach { item ->
                key(item.bookId) { HomeCard(item, isWide, actions.onOpenBook, selection) }
            }
        }
    }
}

/**
 * A Home card (§2.5): square art with the coral line on its bottom edge and the lock, then title, author, "Read by …"
 * on wide pages, and the time left. One `aria-label` names it (C8, §8.2); the line is `aria-hidden`.
 *
 * A [ContinueListeningItem.Loading] keeps its place as a skeleton of the SAME size rather than being dropped: its
 * position row has already arrived, so the book is genuinely coming, and hiding it would shrink the row and then grow
 * it again mid-sync.
 */
@Composable
private fun HomeCard(
    item: ContinueListeningItem,
    isWide: Boolean,
    onOpenBook: (String) -> Unit,
    selection: BookSelection?,
) {
    when (item) {
        is ContinueListeningItem.Loading -> {
            Div(attrs = { classes("home-card", "is-loading") }) {
                Div(attrs = { classes("skel", "home-card-cover-skel") })
                Div(attrs = { classes("skel", "home-skel-line") })
            }
        }

        is ContinueListeningItem.Ready -> {
            ReadyCard(item.book, isWide, onOpenBook, selection)
        }
    }
}

@Composable
private fun ReadyCard(
    book: ContinueListeningBook,
    isWide: Boolean,
    onOpenBook: (String) -> Unit,
    selection: BookSelection?,
) {
    val selecting = selection?.isSelecting == true
    val isSelected = selection?.isSelected(book.bookId) == true
    // While selecting, a press picks instead of opening — one gesture, two jobs, decided by the mode, exactly as the
    // library grid does it.
    val press = { selection.press(book.bookId) { onOpenBook(book.bookId) } }
    Div(attrs = {
        classes("home-card")
        if (isSelected) classes("is-sel")
        tabIndex(0)
        attr("role", if (selecting) "checkbox" else "button")
        if (selecting) attr("aria-checked", isSelected.toString())
        attr("aria-label", homeCardLabel(book, withNarrator = isWide))
        onKeyDown { event ->
            if (event.key == "Enter" || event.key == " ") {
                event.preventDefault()
                press()
            }
        }
        onClick { press() }
    }) {
        Cover(
            title = book.title,
            imageUrl = coverUrl(book.bookId, book.coverHash, width = CARD_COVER_RUNG),
            size = null,
            decorative = true,
            overlay = {
                RestrictedMarker(book.bookId)
                ProgressBar(
                    value = book.progressPercent / PERCENT.toFloat(),
                    look = ProgressLook.Overlay,
                    decorative = true,
                )
            },
        )
        Span(attrs = { classes("home-card-t") }) { Text(book.title) }
        if (book.authorNames.isNotBlank()) Span(attrs = { classes("home-card-a") }) { Text(book.authorNames) }
        if (isWide) {
            book.narratorNames?.takeIf { it.isNotBlank() }?.let { names ->
                Span(attrs = { classes("home-card-n") }) { Text("Read by $names") }
            }
        }
        Span(attrs = { classes("home-card-sub") }) { Text(timeLeftText(book.timeRemainingMs)) }
    }
}

/** "18h 40m left" — Library's wording on every card, whatever its length (open question 6: no "Almost done"). */
internal fun timeLeftText(remainingMs: Long): String = "${DurationFormatter.hoursMinutes(remainingMs.milliseconds)} left"

/** "Babel, by R. F. Kuang, 11% listened, 18h 40m left" (§2.5); wide pages add ", read by …". */
internal fun homeCardLabel(
    book: ContinueListeningBook,
    withNarrator: Boolean,
): String =
    listOfNotNull(
        book.title,
        book.authorNames.takeIf { it.isNotBlank() }?.let { "by $it" },
        book.narratorNames?.takeIf { withNarrator && it.isNotBlank() }?.let { "read by $it" },
        "${book.progressPercent}% listened",
        timeLeftText(book.timeRemainingMs),
    ).joinToString(", ")
