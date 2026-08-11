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

private val dlBuilder = HtmlElementBuilder("dl")
private val dtBuilder = HtmlElementBuilder("dt")
private val ddBuilder = HtmlElementBuilder("dd")

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
