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
 * Path data is copied verbatim from the design project's icon tables (`webPatterns.jsx`'s
 * `X_PATHS`, `webShell.jsx`'s `SHELL_PATHS`) — the design project owns the geometry, this is a
 * mirror.
 */
enum class WebIcon(
    internal val path: String,
    internal val solid: Boolean = false,
) {
    ArrowDown("M12 4v15 M6 13l6 6 6-6"),
    ArrowUp("M12 20V5 M6 11l6-6 6 6"),
    Book("M5 4a1 1 0 0 1 1-1h13v16H6a2 2 0 0 0-2 2V5 M9 3v15"),
    Bookmark("M6 3h12v18l-6-4-6 4z"),
    Check("M4.5 12.5l5 5 10-10"),
    ChevronDown("M6 9l6 6 6-6"),
    ChevronUp("M6 15l6-6 6 6"),
    ChevronLeft("M15 5l-7 7 7 7"),
    ChevronRight("M9 5l7 7-7 7"),
    Clock("M12 12m-9 0a9 9 0 1 0 18 0a9 9 0 1 0-18 0 M12 7v5.5l3.5 2"),
    Cog(
        "M12 12m-3 0a3 3 0 1 0 6 0a3 3 0 1 0-6 0 M12 2.5v3 M12 18.5v3 M2.5 12h3 M18.5 12h3 " +
            "M5.1 5.1l2.1 2.1 M16.8 16.8l2.1 2.1 M18.9 5.1l-2.1 2.1 M7.2 16.8l-2.1 2.1",
    ),
    Compass(
        "M12 12m-9 0a9 9 0 1 0 18 0a9 9 0 1 0-18 0 M15.6 8.4l-2.2 5.2-5.2 2.2 2.2-5.2z",
    ),
    Download("M12 3v11 M8 10.5l4 4 4-4 M4 20h16"),
    Eye(
        "M2.5 12s3.6-6.5 9.5-6.5S21.5 12 21.5 12s-3.6 6.5-9.5 6.5S2.5 12 2.5 12z " +
            "M12 12m-2.75 0a2.75 2.75 0 1 0 5.5 0a2.75 2.75 0 1 0-5.5 0",
    ),
    EyeOff(
        "M4 4l16 16 M9.9 5.7A9.6 9.6 0 0 1 12 5.5c5.9 0 9.5 6.5 9.5 6.5a17 17 0 0 1-3.4 4.1 " +
            "M6.5 7.6A17 17 0 0 0 2.5 12S6.1 18.5 12 18.5a9.4 9.4 0 0 0 3.4-.6 M9.8 9.9a3 3 0 0 0 4.2 4.2",
    ),
    Flame(
        "M12 3c3 3.5 4.5 5.8 4.5 8a4.5 4.5 0 0 1-9 0c0-1.2.4-2.3 1.2-3.4 " +
            "M12 20.5a4 4 0 0 1-2-7.5c.8 1.4 1.5 2 2 2s1.2-.6 2-2a4 4 0 0 1-2 7.5",
    ),
    Hash("M5 9h14 M5 15h14 M10 4l-2 16 M16 4l-2 16"),
    Grip("M5 9h14 M5 15h14"),
    Home("M4 11.4L12 4l8 7.4 M5.6 10v9.6h12.8V10 M9.6 19.6v-5.2h4.8v5.2"),
    Lock("M6 10.5h12v9.5H6z M8.75 10.5V7.5a3.25 3.25 0 0 1 6.5 0v3"),
    LogIn("M14 3h5v18h-5 M4 12h11 M11 8l4 4-4 4"),
    LogOut("M10 3H5v18h5 M9 12h11 M16 8l4 4-4 4"),
    Mail("M3 6h18v12H3z M3 6.5l9 6.5 9-6.5"),
    Merge("M6 4v6a4 4 0 0 0 4 4h8 M14 10l4 4-4 4"),
    PanelLeft("M4 5h16v14H4z M9.5 5v14"),
    Pause("M8 5h3.2v14H8z M12.8 5H16v14h-3.2z", solid = true),
    Pencil("M4 20h4L20 8l-4-4L4 16z M14.5 5.5l4 4"),
    Person(
        "M10 8.5m-3.5 0a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0-7 0 M3 20c0-3.4 3.1-5.5 7-5.5s7 2.1 7 5.5",
    ),
    Play("M8 5l11 7-11 7z", solid = true),
    Search("M11 11m-7 0a7 7 0 1 0 14 0a7 7 0 1 0-14 0 M20 20l-3.6-3.6"),

    // The two below are the set's only entries NOT mirrored from the design project's icon
    // tables — it carries no skip glyphs, so these are the standard counter-clockwise/clockwise
    // rotation arrows the whole category uses. Reconcile them if the design project grows a pair.
    SkipBack("M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8 M3 3v5h5"),
    SkipForward("M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8 M21 3v5h-5"),
    Shield("M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z M9 12l2 2 4-4"),
    Scissors(
        "M6 6l12 12 M18 6L9.5 14.5 M5 17.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0 " +
            "M5 6.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0",
    ),
    Trash("M4 6h16 M9 6V4h6v2 M6 6l1 14h10l1-14 M10 10v7 M14 10v7"),
    Upload("M12 14V3 M8 6.5l4-4 4 4 M4 20h16"),
    UserPlus(
        "M10 8.5m-3.5 0a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0-7 0 M3 20c0-3.4 3.1-5.5 7-5.5s7 2.1 7 5.5 " +
            "M18.5 8v6 M15.5 11h6",
    ),

    // Not mirrored from the design project either — it carries no speaker glyph. The cone plus
    // two arcs is the standard shape for the category; reconcile if the design project grows one.
    Volume("M4 9.5h3.5L12 5.5v13l-4.5-4H4z M16 9.5a4 4 0 0 1 0 5 M18.5 7a7.5 7.5 0 0 1 0 10"),
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
