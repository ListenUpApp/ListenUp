package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.AttrsScope
import org.w3c.dom.Element

/**
 * The icon set, as a closed type rather than the design kit's string keys.
 *
 * `webPatterns.jsx` looks icons up by name and silently renders an empty `<svg>` on a miss — a
 * typo becomes an invisible icon, not an error. An enum moves that to compile time, which is the
 * whole reason the body is Kotlin.
 *
 * Deliberately partial: these are the icons Book Detail actually asks for. The set grows as
 * screens demand entries, rather than porting all forty up front.
 *
 * Path data is copied verbatim from `webPatterns.jsx`'s `X_PATHS` — the design project owns the
 * geometry, this is a mirror.
 */
enum class WebIcon(
    internal val path: String,
    internal val solid: Boolean = false,
) {
    ArrowDown("M12 4v15 M6 13l6 6 6-6"),
    ArrowUp("M12 20V5 M6 11l6-6 6 6"),
    Check("M4.5 12.5l5 5 10-10"),
    Download("M12 3v11 M8 10.5l4 4 4-4 M4 20h16"),
    Hash("M5 9h14 M5 15h14 M10 4l-2 16 M16 4l-2 16"),
    Merge("M6 4v6a4 4 0 0 0 4 4h8 M14 10l4 4-4 4"),
    Pencil("M4 20h4L20 8l-4-4L4 16z M14.5 5.5l4 4"),
    Play("M8 5l11 7-11 7z", solid = true),
    Scissors(
        "M6 6l12 12 M18 6L9.5 14.5 M5 17.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0 " +
            "M5 6.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0",
    ),
    Trash("M4 6h16 M9 6V4h6v2 M6 6l1 14h10l1-14 M10 10v7 M14 10v7"),
    X("M6 6l12 12 M18 6L6 18"),
    ;

    /** The path attribute is one string of space-separated subpaths, split on each `M` command. */
    internal fun subpaths(): List<String> =
        path.split(" M").mapIndexed { index, segment ->
            if (index == 0) segment else "M$segment"
        }
}

/**
 * Renders [icon] as an inline SVG.
 *
 * `stroke="currentColor"` is what lets a single icon inherit the coral of a selected row, the
 * muted ink of a resting one, and the paper of the bulk bar without any per-context variant.
 */
@Composable
fun Icon(
    icon: WebIcon,
    size: Int = DEFAULT_ICON_SIZE,
    strokeWidth: Double = DEFAULT_STROKE_WIDTH,
    attrs: (AttrsScope<Element>.() -> Unit)? = null,
) {
    Svg({
        attr("width", size.toString())
        attr("height", size.toString())
        attr("viewBox", "0 0 24 24")
        attr("fill", "none")
        attr("stroke-linecap", "round")
        attr("stroke-linejoin", "round")
        attr("aria-hidden", "true")
        attr("focusable", "false")
        attrs?.invoke(this)
    }) {
        icon.subpaths().forEach { subpath ->
            Path {
                attr("d", subpath)
                if (icon.solid) {
                    attr("fill", "currentColor")
                    attr("stroke", "none")
                } else {
                    attr("fill", "none")
                    attr("stroke", "currentColor")
                    attr("stroke-width", strokeWidth.toString())
                }
            }
        }
    }
}

private const val DEFAULT_ICON_SIZE = 18

private const val DEFAULT_STROKE_WIDTH = 1.8
