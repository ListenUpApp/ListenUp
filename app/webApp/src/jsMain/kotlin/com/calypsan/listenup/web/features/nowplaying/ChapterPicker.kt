package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement

/**
 * Jump to a chapter without leaving what you are listening to.
 *
 * Book Detail already lists chapters, and this is deliberately not that list. `ChaptersPane` is an
 * editing workbench — a proportional chapter map, a multi-select table, an inspector — and none of
 * that is what someone reaching for chapter 12 mid-book wants. Borrowing it would also tie the
 * player to a surface it otherwise has no reason to know about. A name and somewhere to land is the
 * whole requirement.
 *
 * The modal shell — focus trap, inert page, Escape-to-close — comes from [PlayerDialog], shared
 * with every other player panel.
 */
@Composable
internal fun ChapterPicker(
    open: Boolean,
    chapters: List<TransportChapter>,
    currentIndex: Int?,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    PlayerDialog(
        open = open,
        title = "Chapters",
        panelClass = "chap-dlg",
        onDismiss = onDismiss,
    ) {
        Div(attrs = { classes("chap-list") }) {
            chapters.forEachIndexed { index, chapter ->
                val isCurrent = index == currentIndex
                Button(attrs = {
                    classes("chap-row")
                    if (isCurrent) classes("on")
                    attr("type", "button")
                    // `aria-current`, not just a class: the highlight is information, and a class
                    // alone says nothing to a reader who cannot see it.
                    if (isCurrent) attr("aria-current", "true")
                    onClick { onPick(index) }
                    if (isCurrent) {
                        ref { element ->
                            // A book can carry hundreds of marks. Opening at the top while the
                            // listener is at chapter 200 makes the picker useless exactly when it
                            // is most needed, so the chapter they are in is brought into view.
                            (element as? HTMLElement)?.scrollIntoView(scrollToCentre())
                            onDispose { }
                        }
                    }
                }) {
                    Span(attrs = { classes("chap-n") }) { Text("${index + 1}") }
                    Span(attrs = { classes("chap-t") }) { Text(chapter.title) }
                    Span(attrs = { classes("mono", "chap-at") }) { Text(formatElapsed(chapter.startMs)) }
                }
            }
        }
    }
}

/**
 * `scrollIntoView` options as a JS object.
 *
 * `block: "center"` rather than the default `"start"`: a chapter pinned to the top edge hides the
 * ones before it, and the listener's sense of where they are in the book comes from seeing both
 * sides. `behavior: "auto"` because this runs as the dialog opens — an animated scroll from the top
 * of a long list is motion the listener did not ask for and has to wait out.
 */
private fun scrollToCentre(): dynamic {
    val options = js("{}")
    options.block = "center"
    options.behavior = "auto"
    return options
}
