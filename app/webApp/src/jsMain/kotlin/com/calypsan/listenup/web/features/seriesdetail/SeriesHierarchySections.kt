package com.calypsan.listenup.web.features.seriesdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.calypsan.listenup.client.presentation.seriesdetail.ChildSeriesUi
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesBookSection
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesCrumb
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailUiState
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesSectionKind
import com.calypsan.listenup.client.presentation.seriesedit.AddSubSeriesEvent
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ButtonSize
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.Panel
import com.calypsan.listenup.web.design.ProgressBar
import com.calypsan.listenup.web.design.ProgressLook
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.coverUrl
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.H4
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.Nav
import org.jetbrains.compose.web.dom.Ol
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Button as DomButton

/**
 * The trail above a series: Library, then every series above this one, then this one.
 *
 * A `<nav aria-label="Series path">` around an `<ol>`, the current page marked `aria-current`, the
 * "›" between steps drawn as pseudo-content so it is never read aloud. Every step but the last is a
 * real control with a full-size target; a step pushes the ancestor's page, so Back still returns.
 */
@Composable
internal fun SeriesPathCrumb(
    ancestors: List<SeriesCrumb>,
    current: String,
    onOpenLibrary: () -> Unit,
    onOpenSeries: (String) -> Unit,
) {
    // en.json's series.path_a11y
    Nav(attrs = {
        classes("crumb", "sd-path")
        attr("aria-label", "Series path")
    }) {
        Ol {
            Li { CrumbLink("Library", onOpenLibrary) }
            ancestors.forEach { ancestor ->
                key(ancestor.id) { Li { CrumbLink(ancestor.name) { onOpenSeries(ancestor.id) } } }
            }
            Li {
                Span(attrs = {
                    classes("cur")
                    attr("aria-current", "page")
                }) { Text(current) }
            }
        }
    }
}

@Composable
private fun CrumbLink(
    label: String,
    onOpen: () -> Unit,
) {
    // No `href` — the trail is driven by the router — so the link is made reachable by hand: an `<a>`
    // without one is not a tab stop and ignores Enter. Same contract as the design Breadcrumb.
    A(attrs = {
        attr("role", "link")
        tabIndex(0)
        onClick { onOpen() }
        onKeyDown { event ->
            if (event.key == "Enter") {
                event.preventDefault()
                onOpen()
            }
        }
    }) { Text(label) }
}

/**
 * The series inside this one, as cards in sibling order: each with its count, how far the reader
 * is, and — for a series that has sub-series of its own — a stacked edge and "2 series". An editor
 * also gets an "Add sub-series" tile, disabled offline because the change needs the server.
 */
@Composable
internal fun SubSeriesPanel(
    state: SeriesDetailUiState.Ready,
    actions: SeriesPageActions,
) {
    // en.json's series.subseries
    Panel(title = "Sub-series", trailing = { CountBadge(state.childSeries.size) }) {
        Div(attrs = { classes("sd-subs") }) {
            state.childSeries.forEach { child ->
                key(child.id) {
                    SubSeriesCard(child, coverFor(state, child)) { actions.onOpenSeries(child.id) }
                }
            }
            if (state.canEditMetadata) {
                DomButton(attrs = {
                    classes("sd-sub", "sd-sub-add")
                    attr("type", "button")
                    if (!state.isOnline) {
                        attr("disabled", "")
                        attr("title", "Needs a connection to the server.")
                    }
                    onClick { actions.onAddSubSeriesEvent(AddSubSeriesEvent.Opened) }
                }) {
                    Icon(WebIcon.Plus, size = ADD_ICON)
                    // en.json's series.add_subseries
                    Span { Text("Add sub-series") }
                }
            }
        }
    }
}

@Composable
private fun SubSeriesCard(
    child: ChildSeriesUi,
    imageUrl: String?,
    onOpen: () -> Unit,
) {
    DomButton(attrs = {
        classes("sd-sub")
        if (child.subSeriesCount > 0) classes("is-stack")
        attr("type", "button")
        onClick { onOpen() }
    }) {
        Div(attrs = { classes("sd-sub-cover") }) {
            Cover(title = child.name, imageUrl = imageUrl, size = null, radius = 0, decorative = true)
        }
        Div(attrs = { classes("sd-sub-text") }) {
            Span(attrs = { classes("sd-sub-n") }) { Text(child.name) }
            Span(attrs = { classes("sd-sub-m") }) { Text(childProgressLine(child)) }
            if (child.subSeriesCount > 0) {
                // en.json's series.subseries_count
                Span(attrs = { classes("sd-sub-stack") }) { Text("${child.subSeriesCount} series") }
            }
        }
        // The words above already say "8 books · 2 finished"; the bar restates them for the eye.
        ProgressBar(
            value = if (child.bookCount == 0) 0f else child.finishedCount.toFloat() / child.bookCount,
            look = ProgressLook.Rail,
            decorative = true,
        )
    }
}

/**
 * "8 books · 2 finished", "1 book · Finished", "3 books · Not started" — en.json's
 * `series.finished_count`, `series.book_finished`, `series.not_started`.
 */
internal fun childProgressLine(child: ChildSeriesUi): String {
    val books = seriesBookCount(child.bookCount)
    return when {
        child.isFinished -> "$books · Finished"
        child.isNotStarted -> "$books · Not started"
        else -> "$books · ${child.finishedCount} finished"
    }
}

/**
 * A card's cover: the first book listed under that sub-series on this page, else any loaded book in
 * it. Never `coverPath`, which is a server filesystem path — see the page's own note on the hero.
 */
private fun coverFor(
    state: SeriesDetailUiState.Ready,
    child: ChildSeriesUi,
): String? {
    val listed =
        state.bookSections
            .firstOrNull { section ->
                section.books.isNotEmpty() && (section.seriesId == child.id || section.path.firstOrNull() == child.name)
            }?.books
            ?.firstOrNull()
    val book = listed ?: state.books.firstOrNull { book -> book.series.any { it.seriesId == child.id } }
    return book?.let { coverUrl(it.id.value, it.coverHash, CARD_COVER_RUNG) }
}

/**
 * The books of a parent page, grouped under each sub-series in reading order and then "Also in
 * Cosmere". A group's heading is a link to that sub-series; a sub-series group folds, and a folded
 * one keeps its heading and a "Show all N" that opens it in place (a fully finished one starts
 * folded — the ViewModel decides).
 */
@Composable
internal fun GroupedBooks(
    state: SeriesDetailUiState.Ready,
    actions: SeriesPageActions,
) {
    Div(attrs = { classes("sd-groups") }) {
        state.bookSections.forEach { section ->
            key(section.key) {
                Div(attrs = {
                    classes("sd-group")
                    if (section.depth > 1) classes("is-nested")
                }) {
                    GroupHeading(section, actions)
                    if (section.isCollapsed) {
                        Button(
                            kind = ButtonKind.Ghost,
                            size = ButtonSize.Sm,
                            onClick = { actions.onToggleSection(section.seriesId) },
                            attrs = {
                                classes("sd-show-all")
                                attr("aria-expanded", "false")
                            },
                        ) {
                            // en.json's series.show_all
                            Text("Show all ${section.bookCount}")
                        }
                    } else if (section.books.isNotEmpty()) {
                        Div(attrs = { classes("sd-books") }) {
                            section.books.forEach { book ->
                                key(book.id.value) {
                                    SeriesBookRow(
                                        book = book,
                                        seriesId = section.seriesId,
                                        progress = state.bookProgress[book.id],
                                        isFinished = book.id in state.finishedBookIds,
                                        onOpen = { actions.onOpenBook(book.id.value) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A group's heading: h3 directly under the Books panel's h2, h4 for a nested sub-series. Sticks to
 * the top while its books scroll past.
 */
@Composable
private fun GroupHeading(
    section: SeriesBookSection,
    actions: SeriesPageActions,
) {
    val title =
        when (section.kind) {
            SeriesSectionKind.SUB_SERIES -> section.title

            // en.json's series.also_in
            SeriesSectionKind.OWN_BOOKS -> "Also in ${section.title}"
        }
    val headingContent: @Composable () -> Unit = {
        if (section.kind == SeriesSectionKind.SUB_SERIES) {
            DomButton(attrs = {
                classes("sd-group-link")
                attr("type", "button")
                onClick { actions.onOpenSeries(section.seriesId) }
            }) { Text(title) }
        } else {
            Span(attrs = { classes("sd-group-plain") }) { Text(title) }
        }
    }
    Div(attrs = { classes("sd-group-h") }) {
        if (section.depth > 1) {
            H4(attrs = { classes("sd-group-t") }) { headingContent() }
        } else {
            H3(attrs = { classes("sd-group-t") }) { headingContent() }
        }
        Span(attrs = { classes("sd-group-m") }) { Text(sectionMeta(section)) }
        if (section.isCollapsible && !section.isCollapsed) {
            Button(
                kind = ButtonKind.Icon,
                size = ButtonSize.Sm,
                // en.json's common.collapse
                label = "Collapse ${section.title}",
                onClick = { actions.onToggleSection(section.seriesId) },
                attrs = {
                    classes("sd-group-fold")
                    attr("aria-expanded", "true")
                },
            ) { Icon(WebIcon.ChevronUp, size = FOLD_ICON) }
        }
    }
}

/** "8 books · 2 finished", or just "4 books" when nothing in the group is finished. */
private fun sectionMeta(section: SeriesBookSection): String {
    val books = seriesBookCount(section.bookCount)
    return if (section.finishedCount > 0) "$books · ${section.finishedCount} finished" else books
}

private const val ADD_ICON = 20

private const val FOLD_ICON = 16

private const val CARD_COVER_RUNG = 200
