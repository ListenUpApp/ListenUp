package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.web.design.browserPlatform
import com.calypsan.listenup.web.design.isApplePlatform
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

    /** Shift+←: the previous chapter, by the shared rule (restart after 3 s); nothing in a chapterless book. */
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

/**
 * Where the press happened: whether there is a book to control, the page, the element that had focus, and whether the
 * book has a previous chapter to go to (not in a chapterless book, where previous would only jump to 0:00).
 */
data class KeyContext(
    val isLoaded: Boolean,
    val onPlayingPage: Boolean,
    val target: KeyTarget = KeyTarget.Body,
    val hasPreviousChapter: Boolean = true,
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
    if (!context.isLoaded || standsDown(press, context.target)) return null
    val previousChapter = PlayerKey.PreviousChapter.takeIf { context.hasPreviousChapter }
    return when (press.key) {
        " " -> PlayerKey.PlayPause
        "ArrowLeft" -> if (press.shift) previousChapter else PlayerKey.SkipBack
        "ArrowRight" -> if (press.shift) PlayerKey.NextChapter else PlayerKey.SkipForward
        "Escape" -> PlayerKey.ClosePlayer.takeIf { context.onPlayingPage }
        else -> CHARACTER_KEYS[press.key]
    }
}

/** Whether [press] belongs to someone else: the browser, the OS, the palette, a field, a dialog or a widget. */
private fun standsDown(
    press: KeyPress,
    target: KeyTarget,
): Boolean {
    val handledElsewhere = press.isComposing || press.defaultPrevented || press.ctrl || press.meta || press.alt
    val ownedByTarget =
        target.isEditable ||
            target.isInDialog ||
            (target.activates && press.key in ACTIVATION_KEYS) ||
            (target.inComposite && press.key in ARROW_KEYS)
    return handledElsewhere || ownedByTarget
}

/** The single-character keys: layout-independent characters, letters in either case. */
private val CHARACTER_KEYS: Map<String, PlayerKey> =
    mapOf(
        "<" to PlayerKey.Slower,
        ">" to PlayerKey.Faster,
        "t" to PlayerKey.Sleep,
        "T" to PlayerKey.Sleep,
        "c" to PlayerKey.Chapters,
        "C" to PlayerKey.Chapters,
        "p" to PlayerKey.TogglePlayer,
        "P" to PlayerKey.TogglePlayer,
        "?" to PlayerKey.Shortcuts,
    )

/**
 * One row of the shortcuts sheet, and what a control's tooltip and `aria-keyshortcuts` say: [keys] as the eye reads
 * them, [aria] as `aria-keyshortcuts` spells them, and what the keys do.
 */
data class PlayerShortcut(
    val keys: List<String>,
    val aria: String,
    val description: String,
)

/**
 * The sheet, the tooltips and the handler all read this table, so they cannot disagree (spec §5.8). Search shows its
 * chord as the reader's keyboard labels it, ⌘K on a Mac, iPhone or iPad and Ctrl+K elsewhere ([platform] is the
 * browser's); the palette takes either modifier everywhere, so `aria-keyshortcuts` names both.
 */
fun playerShortcuts(platform: String = browserPlatform()): List<PlayerShortcut> =
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
        PlayerShortcut(
            listOf("/", if (isApplePlatform(platform)) "⌘K" else "Ctrl+K"),
            "/ Control+K Meta+K",
            "Search",
        ),
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
