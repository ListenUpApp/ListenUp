package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.browser.document

/**
 * Names the page in the browser tab, the history menu and a screen reader's page announcement.
 *
 * Every page calls it once, with the same words its H1 carries, or `null` for Home, which is simply
 * "ListenUp". The tab used to say "ListenUp" on every route, so the Back menu was a column of
 * identical entries and a screen reader announced nothing when the page changed (WCAG 2.4.2).
 *
 * Inside the shell the name goes to its [DocumentTitle], which owns `document.title` (while a book plays the tab names
 * that instead, spec §5.7); a page rendered without the shell writes the title itself.
 *
 * A [SideEffect] rather than a keyed effect: it re-asserts on every recomposition, so the title
 * follows a name that arrives late (a book still loading) and the page composed last wins, which is
 * always the one on screen.
 */
@Composable
fun PageTitle(page: String?) {
    val holder = LocalDocumentTitle.current
    if (holder == null) {
        val title = documentTitleFor(page)
        SideEffect { document.title = title }
    } else {
        SideEffect {
            holder.page = page
            // In the same apply as the page, not a frame later: both writers say the holder's one title, so they agree.
            document.title = holder.title
        }
    }
}

/**
 * The one owner of `document.title` in the shell: the page's name, unless a book is playing, when the tab says what is
 * playing ("Bridge Four · The Way of Kings · ListenUp", spec §5.7) on every page. Two writers would flicker.
 */
class DocumentTitle {
    /** The page on screen's name ([PageTitle]); null for Home. */
    var page by mutableStateOf<String?>(null)

    /** What is playing ("Bridge Four · The Way of Kings"), or null when nothing is. */
    var playing by mutableStateOf<String?>(null)

    /** The tab's title now. */
    val title: String get() = playing?.let { "$it · $APP_NAME" } ?: documentTitleFor(page)
}

/** The shell's [DocumentTitle], or null where a page is rendered alone. */
val LocalDocumentTitle = staticCompositionLocalOf<DocumentTitle?> { null }

/**
 * Writes [holder]'s title to the tab whenever it changes (a book starting or pausing, a chapter turning). The shell
 * composes exactly one. A page's own [PageTitle] writes in the same apply as the page, so a new page is never a frame
 * late.
 */
@Composable
fun DocumentTitleWriter(holder: DocumentTitle) {
    LaunchedEffect(holder) {
        snapshotFlow { holder.title }.collect { document.title = it }
    }
}

/** "Library · ListenUp", or just "ListenUp" when there is no page name to put first. */
internal fun documentTitleFor(page: String?): String = if (page.isNullOrBlank()) APP_NAME else "$page · $APP_NAME"

private const val APP_NAME = "ListenUp"
