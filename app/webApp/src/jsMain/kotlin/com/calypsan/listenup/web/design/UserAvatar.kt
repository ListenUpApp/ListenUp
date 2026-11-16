package com.calypsan.listenup.web.design

import com.calypsan.listenup.web.dom.whenFontsLoaded
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement

/**
 * A person, as a circle: their uploaded picture if they have one, their initials on a stable tint
 * if they do not.
 *
 * ⛔ **Most people have no uploaded avatar, so the fallback is the normal path.** `/api/v1/avatars`
 * 404s for anyone who has not set one, which means an `onerror` here is routine rather than
 * exceptional — the monogram it falls back to is a finished avatar, not a broken-image placeholder.
 * Anything that logged or reported that failure would be reporting the majority case.
 *
 * The tint is derived from the name rather than random, so a given person keeps the same colour
 * across sessions and devices — an avatar that changes on refresh reads as a bug even when nothing
 * is wrong. [avatarColor] overrides it where the server has recorded the person's own choice.
 *
 * [monogramOnly] draws the initials without ever asking for a picture. That is not the same as
 * letting the fetch fail: Edit Profile has to show what "remove my photo" will leave behind, and a
 * request for a picture the reader has just asked to delete would show the old one until the
 * server answered.
 *
 * The picture is framed the way its owner chose, read from [LocalAvatarFrames] rather than passed in,
 * so no call site has to know a frame exists.
 *
 * [covered] is how much of the circle an overlapping neighbour hides, as in a stack of faces: the initials then sit
 * in the part left showing, shrinking only as far as they must to fit it.
 */
@Composable
fun UserAvatar(
    userId: String,
    name: String,
    size: Int,
    avatarColor: String? = null,
    monogramOnly: Boolean = false,
    covered: AvatarCover = AvatarCover.None,
) {
    var failed by remember(userId) { mutableStateOf(false) }
    val showMonogram = monogramOnly || failed

    Div(attrs = {
        classes("uav")
        style {
            property("width", "${size}px")
            property("height", "${size}px")
            property("font-size", "${size / MONOGRAM_DIVISOR}px")
            if (showMonogram) property("background", avatarColor ?: avatarTintFor(name))
        }
    }) {
        // Decorative, photo and monogram alike: the person's name is beside this in every call site,
        // so a screen reader reading "BS", or the name a second time, would only add noise.
        if (showMonogram && covered != AvatarCover.None) {
            UncoveredLabel(text = initialsFor(name), size = size, covered = covered, labelClass = "uav-mono")
        } else if (showMonogram) {
            Div(attrs = {
                classes("uav-mono")
                attr("aria-hidden", "true")
            }) { Text(initialsFor(name)) }
        } else {
            val observeFrame = LocalAvatarFrames.current
            val frame by remember(userId, observeFrame) { observeFrame(userId) }.collectAsState(initial = null)
            FramedImg(
                src = avatarUrl(userId),
                alt = "",
                frame = frame,
                classes = listOf("uav-frame"),
                onError = { failed = true },
            )
        }
    }
}

/**
 * How much of a circle's [start] and [end] edges, in CSS pixels, something drawn over it hides, as a neighbour does
 * in a stack of overlapping faces. [None] for a circle nothing overlaps.
 */
data class AvatarCover(
    val start: Int = 0,
    val end: Int = 0,
) {
    /** Holds [None]. */
    companion object {
        /** Nothing covers the circle: its label is centred in the whole of it. */
        val None = AvatarCover()
    }
}

/**
 * A circle's label, [text], fitted into the part of a [size]-pixel circle a neighbour leaves showing: 1px clear of
 * the neighbour's edge and of the circle's own curve, and shrunk only until it fits there. CSS cannot size text to a
 * box, so the label is measured once mounted, and again once the web fonts have loaded and changed its width.
 */
@Composable
internal fun UncoveredLabel(
    text: String,
    size: Int,
    covered: AvatarCover,
    labelClass: String? = null,
) {
    val room = (size - covered.start - covered.end - 2 * UNCOVERED_MARGIN).toDouble()
    key(text, room) {
        Div(attrs = {
            classes(listOfNotNull(labelClass, "uav-covered"))
            attr("aria-hidden", "true")
            style {
                property("padding-inline-start", "${covered.start + UNCOVERED_MARGIN}px")
                property("padding-inline-end", "${covered.end + UNCOVERED_MARGIN}px")
            }
            ref { box ->
                fitLabel(box, room)
                document.whenFontsLoaded { fitLabel(box, room) }
                onDispose {}
            }
        }) { Span { Text(text) } }
    }
}

/** Shrinks [box]'s font until its one line of text is no wider than [room], or half its size, whichever is larger. */
private fun fitLabel(
    box: HTMLElement,
    room: Double,
) {
    val label = box.firstElementChild as? HTMLElement ?: return
    box.style.removeProperty("font-size")
    val width = label.getBoundingClientRect().width
    if (width <= room) return
    val natural =
        window
            .getComputedStyle(box)
            .fontSize
            .removeSuffix("px")
            .toDoubleOrNull() ?: return
    box.style.fontSize = "${maxOf(natural * room / width, natural * MIN_LABEL_SCALE)}px"
}

/** The gap a fitted label keeps from a covering neighbour's edge and from the circle's own curve. */
private const val UNCOVERED_MARGIN = 1

/** How far a fitted label may shrink. */
private const val MIN_LABEL_SCALE = 0.5

/** The monogram sits at roughly two-fifths of the circle, which reads at every size this is used at. */
private const val MONOGRAM_DIVISOR = 2.5
