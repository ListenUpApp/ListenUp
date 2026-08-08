package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.AttrBuilderContext
import org.jetbrains.compose.web.dom.ContentBuilder
import org.jetbrains.compose.web.dom.ElementBuilder
import org.jetbrains.compose.web.dom.TagElement
import org.w3c.dom.Element

/**
 * SVG element builders for Compose HTML, which ships none.
 *
 * The reason this file has to exist: Compose HTML creates elements with `document.createElement`,
 * which puts them in the HTML namespace. An `<svg>` built that way parses and appears in the DOM
 * but never renders — the browser needs `createElementNS`. The failure is silent and looks like a
 * styling problem, so it is worth solving once, here, rather than rediscovering it per icon.
 */
private const val SVG_NAMESPACE = "http://www.w3.org/2000/svg"

private class SvgElementBuilder(
    private val tagName: String,
) : ElementBuilder<Element> {
    private val template: Element by lazy { document.createElementNS(SVG_NAMESPACE, tagName) }

    override fun create(): Element = template.cloneNode(false) as Element
}

private val svgBuilder = SvgElementBuilder("svg")
private val pathBuilder = SvgElementBuilder("path")

/** An `<svg>` root in the SVG namespace. */
@Composable
fun Svg(
    attrs: AttrBuilderContext<Element>? = null,
    content: ContentBuilder<Element>? = null,
) = TagElement(svgBuilder, attrs, content)

/** A `<path>` in the SVG namespace. */
@Composable
fun Path(attrs: AttrBuilderContext<Element>? = null) = TagElement(pathBuilder, attrs, null)
