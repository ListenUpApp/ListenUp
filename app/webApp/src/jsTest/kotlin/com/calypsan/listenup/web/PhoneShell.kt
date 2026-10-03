package com.calypsan.listenup.web

import androidx.compose.runtime.Composable
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.shell.NavEntry
import com.calypsan.listenup.web.shell.NavSection
import com.calypsan.listenup.web.shell.Shell

/** Viewport widths the responsive specs measure at: the smallest phone, a common one, a tablet. */
internal const val SMALL_PHONE = 320

internal const val PHONE = 390

internal const val TABLET = 768

/** WCAG 2.5.5's target size, and the one every platform guideline agrees on. */
internal const val MIN_TARGET_PX = 44.0

/**
 * [content] as a page, inside the real shell — so a spec measures it in the content region a
 * reader actually gets at that width (a 14px gutter and a tab bar on a phone, the rail on a
 * tablet), not flush against a bare frame.
 */
@Composable
internal fun InShell(content: @Composable () -> Unit) {
    Shell(
        sections = listOf(NavSection(listOf(NavEntry("library", "Library", WebIcon.Book)))),
        active = "library",
        onNavigate = {},
    ) { content() }
}

/** How far [frame]'s content region scrolls sideways — the shell's, not the document's. */
internal fun ViewportFrame.contentOverflow(): Int {
    val main = find(".shell-main")
    return main.scrollWidth - main.clientWidth
}

/**
 * Every element that reaches past the content region's right edge, as `tag.class@right` — what a
 * failed [contentOverflow] assertion should name, so the culprit is in the failure message rather
 * than a debugging session.
 */
internal fun ViewportFrame.pastTheEdge(): List<String> {
    val main = find(".shell-main")
    // The content box's right edge: anything past it grows the scrollable area, because a scroller's
    // overflow includes its end padding.
    val edge = rect(main).left + main.clientWidth - css(main, "padding-right").removeSuffix("px").toDouble()
    return listOf("scroll ${main.scrollWidth} > ${main.clientWidth}") +
        findAll(".shell-main *")
            .filter { isShown(it) && rect(it).right > edge + 0.5 }
            .map { "${it.tagName.lowercase()}.${it.getAttribute("class").orEmpty()}@${rect(it).right} > $edge" }
}

/**
 * Text-only zoom to 200% (WCAG 1.4.4): the frame's root font size doubled. Faithful because the sheet
 * sizes all type in rem (`RemFontSizesTest`), which is exactly what a reader's larger default font
 * size changes — while the viewport width, and every media query on it, stays put.
 */
internal fun ViewportFrame.zoomTextTo200() {
    host.ownerDocument!!
        .documentElement!!
        .asDynamic()
        .style.fontSize = "32px"
}

/** Every element under [selector] whose own content is clipped by its box — `tag.class h>clientH`. */
internal fun ViewportFrame.clippedIn(selector: String): List<String> =
    findAll("$selector, $selector *")
        // `.sr-only` is clipped to a pixel on purpose: it is read, never drawn.
        .filter {
            isShown(it) && !it.classList.contains("sr-only") && css(it, "overflow") == "hidden" &&
                it.scrollHeight > it.clientHeight + 1
        }.map {
            "${it.tagName.lowercase()}.${it.getAttribute("class").orEmpty()} ${it.scrollHeight}>${it.clientHeight}"
        }
