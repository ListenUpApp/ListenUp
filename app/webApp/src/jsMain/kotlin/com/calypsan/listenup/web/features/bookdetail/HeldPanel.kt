package com.calypsan.listenup.web.features.bookdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.nav.awaitAnimationFrame
import com.calypsan.listenup.web.nav.focusWithoutScroll
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement

private const val HELD_HEADING_ID = "bd-held-h"
private const val HELD_TILE_ICON_SIZE = 22

/**
 * Book Detail's triage panel for a book held for review: what it is, why there is no Play, and the
 * only things a held book allows (spec §8, §10) — Edit (secondary), Release (primary, it finishes
 * the triage), and Match and Edit chapters (ghost, the secondary tier: fixing a new book's metadata
 * is the point of triage). Canvas `W-Detail-Held`; the second row is §10's.
 *
 * @param isReleasing a release is in flight: Release says so and every action waits
 * @param onRelease asks first — the page shows the confirmation
 * @param onMatch the hero row's Match route (`/book/{id}/match`)
 * @param onEditChapters the Chapters tab's route (`/book/{id}/chapters`)
 */
@Composable
internal fun HeldPanel(
    isReleasing: Boolean,
    onEdit: () -> Unit,
    onRelease: () -> Unit,
    onMatch: () -> Unit,
    onEditChapters: () -> Unit,
) {
    Section(attrs = {
        classes("bd-held")
        attr("aria-labelledby", HELD_HEADING_ID)
    }) {
        Span(attrs = {
            classes("bd-held-tile")
            attr("aria-hidden", "true")
        }) { Icon(WebIcon.Inbox, size = HELD_TILE_ICON_SIZE) }
        Div(attrs = { classes("bd-held-text") }) {
            H2(attrs = {
                id(HELD_HEADING_ID)
                classes("bd-held-t")
            }) { Text("Held for review") }
            P(attrs = { classes("bd-held-sub") }) {
                Text("Hidden from all members. It can’t be played until you release it.")
            }
        }
        Div(attrs = { classes("bd-held-actions") }) {
            Button(kind = ButtonKind.Secondary, onClick = { onEdit() }, enabled = !isReleasing) {
                Icon(WebIcon.Pencil)
                Text("Edit")
            }
            Button(kind = ButtonKind.Primary, onClick = { onRelease() }, enabled = !isReleasing) {
                Icon(if (isReleasing) WebIcon.Clock else WebIcon.Check)
                Text(if (isReleasing) "Releasing…" else "Release")
            }
            // Secondary (spec §10): the two metadata fixes, on the routes the page already has.
            Button(kind = ButtonKind.Ghost, onClick = { onMatch() }, enabled = !isReleasing) {
                Icon(WebIcon.Sparkles)
                Text("Match metadata")
            }
            Button(kind = ButtonKind.Ghost, onClick = { onEditChapters() }, enabled = !isReleasing) {
                Icon(WebIcon.ListOrdered)
                Text("Edit chapters")
            }
        }
    }
}

/**
 * Hands focus to the page's H1 when a release lands.
 *
 * The held panel holds the Release button, so a successful release unmounts the very control that
 * had focus, and the browser drops focus to `<body>`: a screen reader says nothing, and the next
 * Tab starts from the top of the document. Landing on the heading is the house pattern for "the
 * page under you changed" (see `FocusPageOnNavigation`), and the heading is the one thing on the
 * page that names what you are now looking at — the same book, now ordinary.
 *
 * It acts only on the held → released edge, a frame later (the confirmation dialog hands focus back
 * as it closes), and only when focus really was lost: a reader who has already moved on keeps
 * their place.
 */
@Composable
internal fun FocusHeadingOnRelease(
    bookId: String,
    isHeld: Boolean,
    page: () -> HTMLElement?,
) {
    val memory = remember(bookId) { HeldMemory(wasHeld = isHeld) }
    LaunchedEffect(bookId, isHeld) {
        val released = memory.wasHeld && !isHeld
        memory.wasHeld = isHeld
        if (!released) return@LaunchedEffect
        awaitAnimationFrame()
        val active = document.activeElement
        if (active != null && active != document.body) return@LaunchedEffect
        val heading = page()?.querySelector(".bd-head h1") as? HTMLElement ?: return@LaunchedEffect
        focusWithoutScroll(heading)
    }
}

/** Plain holder rather than state: remembering the last value must not itself recompose. */
private class HeldMemory(
    var wasHeld: Boolean,
)

/** The page's own root, read lazily by [FocusHeadingOnRelease] — never a document-wide query. */
internal class PageRoot {
    var element: HTMLElement? = null
}
