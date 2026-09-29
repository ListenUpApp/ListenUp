package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Header
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * The top of a page: its one H1, and the words and controls that belong beside it.
 *
 * Every page used to write its own — 31 private title rules, drifting between 1.5rem and 2.1rem, and
 * some pages (a profile or a shelf still loading, one that failed) with no H1 at all. This is the
 * only place a page's H1 is made, so it is also:
 * - the tab's name: it calls [PageTitle] with [documentTitle], which defaults to [title] and exists
 *   for the pages whose tab says more than their heading ("Edit The Hobbit" over "The Hobbit"), or,
 *   as `null`, for Home, whose tab is simply "ListenUp";
 * - the focus target: `FocusPageOnNavigation` lands keyboard focus on the page's H1 when the reader
 *   navigates, and this is that H1.
 *
 * [pending] is for a page whose title is still being read — a book, a person, a shelf. The H1 is
 * there from the first frame, named with [title] (the kind of thing: "Profile") for a screen reader,
 * with a skeleton bar where the name will appear. A page that is loading still has a name.
 *
 * [display] is the ramp's display step, for the few pages whose title is the subject rather than a
 * label: Home's greeting, the sign-in screens, and an entity's hero beside its artwork.
 *
 * [details] renders under the subtitle — a shelf's counts, a profile's numbers. [actions] sit at the
 * header's trailing edge and wrap beneath the title when there is no room.
 */
@Composable
fun PageHeader(
    title: String,
    eyebrow: String? = null,
    subtitle: String? = null,
    documentTitle: String? = title,
    display: Boolean = false,
    pending: Boolean = false,
    details: (@Composable () -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    PageTitle(documentTitle)
    Header(attrs = {
        classes("page-h")
        if (display) classes("is-display")
    }) {
        Div(attrs = { classes("page-h-text") }) {
            eyebrow?.let { P(attrs = { classes("page-eyebrow") }) { Text(it) } }
            H1(attrs = { classes("page-t") }) {
                if (pending) {
                    Span(attrs = { classes("sr-only") }) { Text(title) }
                    Span(attrs = {
                        classes("skel", "page-t-skel")
                        attr("aria-hidden", "true")
                    })
                } else {
                    Text(title)
                }
            }
            subtitle?.let { P(attrs = { classes("page-sub") }) { Text(it) } }
            details?.invoke()
        }
        actions?.let { Div(attrs = { classes("page-h-actions") }) { it() } }
    }
}
