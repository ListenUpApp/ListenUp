package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.attributes.alt
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Text

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
 */
@Composable
fun UserAvatar(
    userId: String,
    name: String,
    size: Int,
    avatarColor: String? = null,
) {
    var failed by remember(userId) { mutableStateOf(false) }

    Div(attrs = {
        classes("uav")
        style {
            property("width", "${size}px")
            property("height", "${size}px")
            property("font-size", "${size / MONOGRAM_DIVISOR}px")
            if (failed) property("background", avatarColor ?: avatarTintFor(name))
        }
    }) {
        if (failed) {
            // Decorative: the person's name is beside this in every call site, so a screen reader
            // reading "BS" after "Brandon Sanderson" would only add noise.
            Div(attrs = {
                classes("uav-mono")
                attr("aria-hidden", "true")
            }) { Text(initialsFor(name)) }
        } else {
            Img(
                src = avatarUrl(userId),
                attrs = {
                    classes("uav-img")
                    alt(name)
                    attr("decoding", "async")
                    addEventListener("error") { failed = true }
                },
            )
        }
    }
}

/** The monogram sits at roughly two-fifths of the circle, which reads at every size this is used at. */
private const val MONOGRAM_DIVISOR = 2.5
