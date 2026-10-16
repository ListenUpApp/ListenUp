package com.calypsan.listenup.web.features.discover

import androidx.compose.runtime.Composable
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.UnderHeading
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement

/*
 * Discover's section furniture: the heading-and-body frame every section shares, its loading and
 * failure states, the person link, and the sizes and attribute names the sections have in common.
 */

/** Cover size for a discovery card, in px. Square, like the artwork. */
internal const val CARD_COVER_WIDTH = 140

/** A listener card's avatar, in px. */
internal const val LISTENER_AVATAR = 30

// ── Shared section furniture ────────────────────────────────────────────────

/**
 * A Discover section: its H2, an optional quiet line under it ([subtitle]), optional [actions] at the
 * heading's end (Shuffle, "Newest in Library →"), then its body.
 *
 * [styleClass] places it (a grid area, or the leaderboard's card); [id] makes it a fragment target;
 * [onMounted] hands the section element to a landing that must scroll it into view.
 */
@Composable
internal fun Section(
    heading: String,
    subtitle: String? = null,
    id: String? = null,
    styleClass: String? = null,
    actions: (@Composable () -> Unit)? = null,
    onMounted: ((HTMLElement) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Div(attrs = {
        classes("disc-section")
        styleClass?.let { classes(it) }
        id?.let { id(it) }
        onMounted?.let { mounted ->
            ref { element ->
                mounted(element)
                onDispose {}
            }
        }
    }) {
        Div(attrs = { classes("disc-section-head") }) {
            Div(attrs = { classes("disc-section-titles") }) {
                H2(attrs = { classes("disc-section-h") }) { Text(heading) }
                subtitle?.let { P(attrs = { classes("disc-sub") }) { Text(it) } }
            }
            actions?.let { Div(attrs = { classes("disc-section-actions") }) { it() } }
        }
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

/** A section's own heading is an H2, so anything headed inside it is an H3. */
internal const val SECTION_HEADING_LEVEL = 2
