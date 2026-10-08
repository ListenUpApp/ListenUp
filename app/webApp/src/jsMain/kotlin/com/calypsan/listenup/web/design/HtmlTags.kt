package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.AttrBuilderContext
import org.jetbrains.compose.web.dom.ContentBuilder
import org.jetbrains.compose.web.dom.ElementBuilder
import org.jetbrains.compose.web.dom.TagElement
import org.w3c.dom.HTMLElement

/**
 * HTML elements Compose HTML does not wrap.
 *
 * Its `dom` package covers the common tags but stops short of the description list. That matters
 * here rather than being a curiosity: Book Detail's "Details" panel is a set of term/value pairs,
 * and `<dl>`/`<dt>`/`<dd>` is what announces them as pairs to a screen reader. Substituting divs
 * would look identical and read as an undifferentiated run of text.
 */
private class HtmlElementBuilder(
    private val tagName: String,
) : ElementBuilder<HTMLElement> {
    private val template: HTMLElement by lazy { document.createElement(tagName) as HTMLElement }

    override fun create(): HTMLElement = template.cloneNode(false) as HTMLElement
}

private val dialogBuilder = HtmlElementBuilder("dialog")
private val dlBuilder = HtmlElementBuilder("dl")
private val strongBuilder = HtmlElementBuilder("strong")
private val dtBuilder = HtmlElementBuilder("dt")
private val ddBuilder = HtmlElementBuilder("dd")
private val detailsBuilder = HtmlElementBuilder("details")
private val summaryBuilder = HtmlElementBuilder("summary")

/** A description list. */
@Composable
fun Dl(
    attrs: AttrBuilderContext<HTMLElement>? = null,
    content: ContentBuilder<HTMLElement>? = null,
) = TagElement(dlBuilder, attrs, content)

/** A description term. */
@Composable
fun Dt(
    attrs: AttrBuilderContext<HTMLElement>? = null,
    content: ContentBuilder<HTMLElement>? = null,
) = TagElement(dtBuilder, attrs, content)

/** A description value. */
@Composable
fun Dd(
    attrs: AttrBuilderContext<HTMLElement>? = null,
    content: ContentBuilder<HTMLElement>? = null,
) = TagElement(ddBuilder, attrs, content)

/**
 * Strong importance.
 *
 * Compose HTML wraps `<b>`, which is presentational — "bold this" — where a book description's
 * `**…**` is the author's emphasis and should reach a screen reader as such.
 */
@Composable
fun Strong(
    attrs: AttrBuilderContext<HTMLElement>? = null,
    content: ContentBuilder<HTMLElement>? = null,
) = TagElement(strongBuilder, attrs, content)

/**
 * A modal `<dialog>`.
 *
 * The real element rather than a div with `role="dialog"`, because `showModal()` brings three
 * things a div would have to reimplement and usually reimplements badly: focus is trapped inside,
 * everything behind it goes inert, and Escape closes it. See [com.calypsan.listenup.web.design.ConfirmDialog].
 */
@Composable
fun Dialog(
    attrs: AttrBuilderContext<HTMLElement>? = null,
    content: ContentBuilder<HTMLElement>? = null,
) = TagElement(dialogBuilder, attrs, content)

/**
 * A disclosure the browser draws and the keyboard already knows: `<details>`, opened and closed by its
 * [Summary] with Enter or Space, with no script. Match details' "6 fields already match" is one.
 */
@Composable
fun Details(
    attrs: AttrBuilderContext<HTMLElement>? = null,
    content: ContentBuilder<HTMLElement>? = null,
) = TagElement(detailsBuilder, attrs, content)

/** The always-visible line of a [Details] — the control that opens it. */
@Composable
fun Summary(
    attrs: AttrBuilderContext<HTMLElement>? = null,
    content: ContentBuilder<HTMLElement>? = null,
) = TagElement(summaryBuilder, attrs, content)
