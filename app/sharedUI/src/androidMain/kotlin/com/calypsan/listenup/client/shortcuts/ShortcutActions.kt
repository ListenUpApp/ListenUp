package com.calypsan.listenup.client.shortcuts

/**
 * Action constants for app shortcuts.
 *
 * These actions are used in:
 * - Static shortcuts (shortcuts.xml) - launched from long-press menu
 * - Dynamic shortcuts (ListenUpShortcutManager) - recent books
 * - Intent handling (MainActivity) - processes the actions
 *
 * Design philosophy:
 * - Actions should feel instant - user taps, magic happens
 * - Each action has a clear, single purpose
 * - Actions work whether app is running or not
 */
object ShortcutActions {
    // ═══════════════════════════════════════════════════════════════════════════
    // ACTION CONSTANTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Resume listening to the most recent book.
     *
     * Behavior:
     * - Finds the last played book from local database
     * - Starts playback from saved position
     * - Opens the Now Playing screen
     * - If no book exists, opens the library
     */
    const val RESUME = "com.calypsan.listenup.action.RESUME"

    /**
     * A tapped push notification, routed by decoding [EXTRA_PUSH_PAYLOAD] through the shared
     * notification target mapping.
     *
     * Lives here rather than in the push package because [com.calypsan.listenup.client.MainActivity]
     * dispatches it through the same `when (intent.action)` as the shortcut actions — one entry
     * point, one dispatch, no second mechanism to keep in step.
     */
    const val PUSH_TAP = "com.calypsan.listenup.action.PUSH_TAP"

    /**
     * Play a specific book.
     *
     * Used by dynamic shortcuts for recently played books.
     * Requires EXTRA_BOOK_ID to identify which book to play.
     *
     * Behavior:
     * - Starts playback of the specified book
     * - Resumes from saved position if available
     * - Opens the Now Playing screen
     */
    const val PLAY_BOOK = "com.calypsan.listenup.action.PLAY_BOOK"

    /**
     * Open library search.
     *
     * Behavior:
     * - Opens the app to the Library tab
     * - Focuses the search field for immediate input
     */
    const val SEARCH = "com.calypsan.listenup.action.SEARCH"

    /**
     * Quick set sleep timer.
     *
     * Behavior:
     * - If currently playing: shows sleep timer options
     * - If not playing: resumes last book + sets 30-min timer
     */
    const val SLEEP_TIMER = "com.calypsan.listenup.action.SLEEP_TIMER"

    /**
     * Resume an in-progress ABS import in the import flow.
     *
     * Requires EXTRA_IMPORT_ID to identify which import to open.
     */
    const val NAVIGATE_TO_ABS_IMPORT = "com.calypsan.listenup.action.NAVIGATE_TO_ABS_IMPORT"

    // ═══════════════════════════════════════════════════════════════════════════
    // INTENT EXTRAS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Book ID extra for PLAY_BOOK action.
     *
     * Value type: String
     */
    const val EXTRA_BOOK_ID = "book_id"

    /**
     * Timer duration extra for SLEEP_TIMER action.
     *
     * Value type: Int (minutes)
     * If not provided, uses default duration.
     */
    const val EXTRA_TIMER_MINUTES = "timer_minutes"

    /**
     * Import ID extra for NAVIGATE_TO_ABS_IMPORT action.
     *
     * Value type: String
     */
    const val EXTRA_IMPORT_ID = "import_id"

    // ═══════════════════════════════════════════════════════════════════════════
    // SHORTCUT IDS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Static shortcut IDs - must match shortcuts.xml.
     */
    const val SHORTCUT_ID_RESUME = "resume_listening"
    const val SHORTCUT_ID_SEARCH = "search_library"
    const val SHORTCUT_ID_SLEEP = "sleep_timer"

    /**
     * Dynamic shortcut ID prefix for recent books.
     * Full ID format: "book_{bookId}"
     */
    const val SHORTCUT_ID_BOOK_PREFIX = "book_"

    /**
     * Maximum number of dynamic shortcuts for books.
     * Android allows 4 dynamic shortcuts total.
     */
    const val MAX_BOOK_SHORTCUTS = 4

    /**
     * The tapped notification's whole [com.calypsan.listenup.api.push.PushPayload], encoded with
     * `contractJson` — the STABLE wire format, so R8 renames cannot break routing in a release
     * build. `MainActivity` decodes it and routes through the same target mapping as the in-app
     * notification list; per-type extras would be a second protocol to keep in step.
     */
    const val EXTRA_PUSH_PAYLOAD = "push_payload"
}
