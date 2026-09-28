package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import kotlinx.browser.document

/**
 * Names the page in the browser tab, the history menu and a screen reader's page announcement.
 *
 * Every page calls it once, with the same words its H1 carries, or `null` for Home, which is simply
 * "ListenUp". The tab used to say "ListenUp" on every route, so the Back menu was a column of
 * identical entries and a screen reader announced nothing when the page changed (WCAG 2.4.2).
 *
 * A [SideEffect] rather than a keyed effect: it re-asserts on every recomposition, so the title
 * follows a name that arrives late (a book still loading) and the page composed last wins, which is
 * always the one on screen.
 */
@Composable
fun PageTitle(page: String?) {
    val title = documentTitleFor(page)
    SideEffect { document.title = title }
}

/** "Library · ListenUp", or just "ListenUp" when there is no page name to put first. */
internal fun documentTitleFor(page: String?): String = if (page.isNullOrBlank()) APP_NAME else "$page · $APP_NAME"

private const val APP_NAME = "ListenUp"
