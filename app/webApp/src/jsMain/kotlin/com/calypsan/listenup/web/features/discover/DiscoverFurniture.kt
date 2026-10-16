package com.calypsan.listenup.web.features.discover

import androidx.compose.runtime.Composable
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.UnderHeading
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text

/*
 * Discover's section furniture: the heading-and-body frame every section shares, its loading and
 * failure states, the person link, and the sizes and attribute names the sections have in common.
 */

/** Cover size for a discovery card, in px. Square, like the artwork. */
internal const val CARD_COVER_WIDTH = 140

/**
 * A person's name, as the way to reach them.
 *
 * A `<button>` rather than the `<span>` these all used to be: it is a real control, so it is
 * focusable, announced, and reachable without a mouse. It keeps the span's class so the surrounding
 * layout is unchanged — only the element and its behaviour differ.
 */
@Composable
internal fun PersonLink(
    name: String,
    userId: String,
    onOpenProfile: (String) -> Unit,
    styleClass: String,
) {
    Button(attrs = {
        classes(styleClass, "disc-person")
        attr(ATTR_TYPE, VALUE_BUTTON)
        onClick { event ->
            // The listener card around this is itself a button that opens the book. Without this
            // the browser would run both and the reader would land on the book they did not click.
            event.stopPropagation()
            onOpenProfile(userId)
        }
    }) { Text(name) }
}

/** Cover width for a listener row's book, in px. */
internal const val LISTENER_COVER_WIDTH = 56

// ── Shared section furniture ────────────────────────────────────────────────

@Composable
internal fun Section(
    heading: String,
    content: @Composable () -> Unit,
) {
    Div(attrs = { classes("disc-section") }) {
        H2(attrs = { classes("disc-section-h") }) { Text(heading) }
        UnderHeading(level = SECTION_HEADING_LEVEL, content = content)
    }
}

/**
 * The pre-first-emission state, said with a shape rather than a word.
 *
 * Deliberately silent about why: at this point the section does not know whether it is empty,
 * loading slowly or broken, and guessing out loud is how a page ends up lying.
 */
@Composable
internal fun SectionSkeleton() {
    Div(attrs = { classes("skel", "disc-skel") })
}

/**
 * A section that failed, and — when [onRetry] is given — the one way back without reloading the tab.
 *
 * [onRetry] is passed only where the ViewModel can actually recover that section. A "Try again"
 * over a flow that has already ended would be a control that does nothing, which is worse than none.
 */
@Composable
internal fun SectionError(
    message: String,
    onRetry: (() -> Unit)? = null,
) {
    P(attrs = { classes("disc-error") }) { Text(message) }
    onRetry?.let { retry ->
        Button(
            kind = ButtonKind.Secondary,
            onClick = retry,
            attrs = { classes("disc-retry") },
        ) { Text("Try again") }
    }
}

internal const val ATTR_TYPE = "type"

internal const val VALUE_BUTTON = "button"

/** A section's own heading is an H2, so anything headed inside it is an H3. */
internal const val SECTION_HEADING_LEVEL = 2
