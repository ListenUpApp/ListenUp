package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.H4
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Where a page, a section or a panel has nothing to show — or cannot show it — and says so.
 *
 * Web had one `.empty` rule and 22 private cousins (`.brw-none`, `.mdx-empty`, `.dev-none`…), each
 * re-deciding padding, colour and heading level. They come in three genuinely different shapes, so
 * this is one composable with three [look]s rather than one look forced on all of them:
 * - [EmptyLook.Page]: the centred block a whole page or section shows — icon, heading, body, action;
 * - [EmptyLook.Inset]: the same parts, left-aligned on an inset card, for a working surface (an
 *   editor, a wizard) where a centred block would float away from the controls around it;
 * - [EmptyLook.Inline]: one quiet sentence inside a list or a form ("No devices signed in"). It is a
 *   paragraph, not a heading: a one-line note must not become an entry in the page's outline.
 *
 * [marker] is an extra class naming which state this is (`is-error`, `is-none`) for a stylesheet or
 * a spec to find it by. [announce] makes the body a `role=alert` region, for a failure that arrives
 * after the reader acted and away from their focus — otherwise they press the button and hear nothing.
 *
 * The heading's level is not the caller's to pick. It is one below the nearest heading above it —
 * [LocalHeadingLevel], which a [Panel] or a titled section raises — so an empty state on a page is an
 * H2 and one inside a section is an H3, and the outline never skips a level.
 */
@Composable
fun EmptyState(
    title: String,
    body: String? = null,
    icon: WebIcon? = null,
    look: EmptyLook = EmptyLook.Page,
    marker: String? = null,
    announce: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    if (look == EmptyLook.Inline) {
        P(attrs = {
            classes("empty-line")
            marker?.let { classes(it) }
        }) { Text(title) }
        return
    }
    Div(attrs = {
        classes("empty")
        if (look == EmptyLook.Inset) classes("is-inset")
        marker?.let { classes(it) }
    }) {
        icon?.let { Div(attrs = { classes("ico") }) { Icon(it, size = EMPTY_ICON_SIZE) } }
        EmptyHeading(level = (LocalHeadingLevel.current + 1).coerceAtMost(MAX_EMPTY_LEVEL), text = title)
        body?.let {
            P(attrs = { if (announce) attr("role", "alert") }) { Text(it) }
        }
        action?.let { Div(attrs = { classes("empty-act") }) { it() } }
    }
}

/** The three shapes of "nothing here" — see [EmptyState]. */
enum class EmptyLook { Page, Inset, Inline }

/**
 * A page or region whose content is on its way: a skeleton, and — for a screen reader, which a
 * skeleton says nothing to — [label] in a polite live region.
 *
 * Loading is a skeleton, never a bare "Loading…" line in an empty-state block: the block reserves
 * the wrong shape, then the real content arrives and the page jumps.
 */
@Composable
fun LoadingState(label: String = "Loading…") {
    Div(attrs = {
        classes("loading")
        attr("role", "status")
    }) {
        Span(attrs = { classes("sr-only") }) { Text(label) }
        Div(attrs = {
            classes("skel", "loading-block")
            attr("aria-hidden", "true")
        })
    }
}

/**
 * The level of the heading the current content sits under: 1 on a page (its H1), 2 inside a panel or
 * a titled section. [EmptyState] reads it to pick its own level.
 */
val LocalHeadingLevel = staticCompositionLocalOf { 1 }

/** Renders [content] as the body of a section headed at [level], so nested empties nest under it. */
@Composable
fun UnderHeading(
    level: Int,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalHeadingLevel provides level) { content() }
}

@Composable
private fun EmptyHeading(
    level: Int,
    text: String,
) {
    when (level) {
        2 -> H2 { Text(text) }
        3 -> H3 { Text(text) }
        else -> H4 { Text(text) }
    }
}

private const val EMPTY_ICON_SIZE = 24

/** H4 is the floor: nothing on web nests a panel inside a section inside a panel. */
private const val MAX_EMPTY_LEVEL = 4
