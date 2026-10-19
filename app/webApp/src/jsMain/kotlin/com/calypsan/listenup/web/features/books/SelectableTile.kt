package com.calypsan.listenup.web.features.books

import androidx.compose.runtime.Composable
import com.calypsan.listenup.web.design.AppLink
import org.jetbrains.compose.web.dom.Div

/**
 * A book tile that is a link to the book until a selection starts, and then a checkbox (plan C8).
 *
 * While selecting, the tile is one focusable `role=checkbox` element whose `aria-checked` is the
 * string "true" or "false" — never a templated `checked` attribute (DSw) — and Space or Enter toggles
 * it, as a click does. Otherwise it is the same [AppLink] it always was, opening the book.
 *
 * @param label The tile's accessible name in both modes.
 * @param classes The tile's classes; a picked tile adds `is-sel` (the coral ring and check).
 */
@Composable
internal fun SelectableTile(
    bookId: String,
    selection: BookSelection?,
    label: String?,
    href: String,
    onOpen: () -> Unit,
    classes: List<String>,
    content: @Composable () -> Unit,
) {
    val isPicked = selection?.isSelected(bookId) == true
    if (selection != null && selection.isSelecting) {
        Div(attrs = {
            classes(classes)
            classes("sel-tile")
            if (isPicked) classes("is-sel")
            attr("role", "checkbox")
            attr("aria-checked", isPicked.toString())
            label?.let { attr("aria-label", it) }
            tabIndex(0)
            onClick { selection.onToggle(bookId) }
            onKeyDown { event ->
                if (event.key == " " || event.key == "Enter") {
                    event.preventDefault()
                    selection.onToggle(bookId)
                }
            }
        }) { content() }
    } else {
        AppLink(href = href, onOpen = onOpen, attrs = {
            classes(classes)
            if (isPicked) classes("is-sel")
            label?.let { attr("aria-label", it) }
        }) { content() }
    }
}
