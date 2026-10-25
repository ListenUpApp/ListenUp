package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.web.shell.KeyTarget
import org.w3c.dom.events.KeyboardEvent

/** What a player shortcut does (spec §5.8's web table). */
enum class PlayerKey {
    /** Space: play or pause. */
    PlayPause,

    /** ←: back the interval, × speed. */
    SkipBack,

    /** →: forward the interval, × speed. */
    SkipForward,

    /** Shift+←: the previous chapter, by the shared rule (restart after 3 s). */
    PreviousChapter,

    /** Shift+→: the next chapter; nothing at the last. */
    NextChapter,

    /** `<`: 0.05 slower. */
    Slower,

    /** `>`: 0.05 faster. */
    Faster,

    /** T: the sleep timer. */
    Sleep,

    /** C: the chapters. */
    Chapters,

    /** P: open Now Playing, or close it there. */
    TogglePlayer,

    /** Esc on Now Playing: close it. */
    ClosePlayer,

    /** ?: the keyboard shortcuts. */
    Shortcuts,
}

/** One key press, as the table reads it. */
data class KeyPress(
    val key: String,
    val shift: Boolean = false,
    val ctrl: Boolean = false,
    val meta: Boolean = false,
    val alt: Boolean = false,
    val isComposing: Boolean = false,
    val defaultPrevented: Boolean = false,
) {
    /** Reads [event]. */
    constructor(event: KeyboardEvent) : this(
        key = event.key,
        shift = event.shiftKey,
        ctrl = event.ctrlKey,
        meta = event.metaKey,
        alt = event.altKey,
        isComposing = event.isComposing,
        defaultPrevented = event.defaultPrevented,
    )
}

/** Where the press happened: whether there is a book to control, the page, and the element that had focus. */
data class KeyContext(
    val isLoaded: Boolean,
    val onPlayingPage: Boolean,
    val target: KeyTarget = KeyTarget.Body,
)

/**
 * The player shortcut [press] asks for in [context], or null when the player stands down (spec §5.8): nothing loaded;
 * a press being composed, already handled, or with Ctrl, ⌘ or Alt (those belong to the browser, the OS and the
 * palette); focus in a field or a dialog; Space on a control Space activates; an arrow in a widget the arrows move
 * through. Pure.
 */
fun playerKeyFor(
    press: KeyPress,
    context: KeyContext,
): PlayerKey? {
    val target = context.target
    val standsDown =
        !context.isLoaded ||
            press.isComposing ||
            press.defaultPrevented ||
            press.ctrl ||
            press.meta ||
            press.alt ||
            target.isEditable ||
            target.isInDialog ||
            (target.activates && press.key in ACTIVATION_KEYS) ||
            (target.inComposite && press.key in ARROW_KEYS)
    if (standsDown) return null
    return when (press.key) {
        " " -> PlayerKey.PlayPause
        "ArrowLeft" -> if (press.shift) PlayerKey.PreviousChapter else PlayerKey.SkipBack
        "ArrowRight" -> if (press.shift) PlayerKey.NextChapter else PlayerKey.SkipForward
        "<" -> PlayerKey.Slower
        ">" -> PlayerKey.Faster
        "t", "T" -> PlayerKey.Sleep
        "c", "C" -> PlayerKey.Chapters
        "p", "P" -> PlayerKey.TogglePlayer
        "?" -> PlayerKey.Shortcuts
        "Escape" -> PlayerKey.ClosePlayer.takeIf { context.onPlayingPage }
        else -> null
    }
}

/**
 * One row of the shortcuts sheet, and what a control's tooltip and `aria-keyshortcuts` say: [keys] as the eye reads
 * them, [aria] as `aria-keyshortcuts` spells them, and what the keys do.
 */
data class PlayerShortcut(
    val keys: List<String>,
    val aria: String,
    val description: String,
)

/** The sheet, the tooltips and the handler all read this table, so they cannot disagree (spec §5.8). */
val PLAYER_SHORTCUTS: List<PlayerShortcut> =
    listOf(
        PlayerShortcut(listOf("Space"), "Space", "Play or pause"),
        PlayerShortcut(listOf("←", "→"), "ArrowLeft ArrowRight", "Skip back or forward"),
        PlayerShortcut(listOf("Shift+←", "Shift+→"), "Shift+ArrowLeft Shift+ArrowRight", "Previous or next chapter"),
        PlayerShortcut(listOf("<", ">"), "< >", "Slower or faster"),
        PlayerShortcut(listOf("T"), "T", "Sleep timer"),
        PlayerShortcut(listOf("C"), "C", "Chapters"),
        PlayerShortcut(listOf("P"), "P", "Open or close Now Playing"),
        PlayerShortcut(listOf("Esc"), "Escape", "Close Now Playing"),
        PlayerShortcut(listOf("?"), "?", "Keyboard shortcuts"),
        PlayerShortcut(listOf("/", "⌘K"), "/ Meta+K", "Search"),
    )

/** The `aria-keyshortcuts` value of each control the table names. */
object PlayerKeyAria {
    const val PLAY_PAUSE = "Space"
    const val SKIP_BACK = "ArrowLeft"
    const val SKIP_FORWARD = "ArrowRight"
    const val PREVIOUS_CHAPTER = "Shift+ArrowLeft"
    const val NEXT_CHAPTER = "Shift+ArrowRight"
    const val SPEED = "< >"
    const val SLEEP = "T"
    const val CHAPTERS = "C"
    const val OPEN_PLAYER = "P"
    const val CLOSE_PLAYER = "P Escape"
    const val SHORTCUTS = "?"
}

/** The attribute a control a key presses carries, with the key: T, C and ? press the control the reader sees. */
const val DATA_PLAYER_KEY = "data-player-key"

private val ACTIVATION_KEYS = setOf(" ", "Enter")

private val ARROW_KEYS = setOf("ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown")
