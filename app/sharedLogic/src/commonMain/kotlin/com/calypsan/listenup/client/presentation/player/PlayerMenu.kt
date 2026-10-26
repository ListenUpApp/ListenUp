package com.calypsan.listenup.client.presentation.player

/** One row of Now Playing's More menu (spec §5.5.7). Each platform supplies its words, its glyph and the action. */
sealed interface PlayerMenuItem {
    /** The loaded book's detail. */
    data object GoToBook : PlayerMenuItem

    /** The series page of the book's first series. */
    data class GoToSeries(
        val seriesId: String,
    ) : PlayerMenuItem

    /** The book's first PDF: the in-app reader on native, a new tab on web. */
    data class OpenPdf(
        val documentId: String,
    ) : PlayerMenuItem

    /** The boost sheet; the row reads the current value ("Volume boost · +6 dB", or "· Off"). */
    data class VolumeBoost(
        val boostDb: Float,
    ) : PlayerMenuItem

    /**
     * The keyboard shortcuts sheet. Only the web asks for it: Android has no app shortcuts, and the iPad lists its
     * keys in the system's own ⌘-hold overlay.
     */
    data object KeyboardShortcuts : PlayerMenuItem

    /** Stop and unload the book. No confirmation: the place is kept (P31). */
    data object CloseBook : PlayerMenuItem
}

/**
 * One group of the More menu, drawn after a divider from the one before it.
 *
 * @property items The group's rows, in order. Never empty: an empty group is left out.
 * @property isDestructive True only for Close book's group, which platforms draw in the error colour or destructive role.
 */
data class PlayerMenuGroup(
    val items: List<PlayerMenuItem>,
    val isDestructive: Boolean = false,
)

/** Builds the More menu. An object, so Swift Export exposes it as `PlayerMenus.shared.moreMenu(...)`. */
object PlayerMenus {
    /**
     * The More menu for [state] (§5.5.7): Go to book; Go to series when [includesSeries] and the book has one; Open PDF
     * when it has one; Volume boost; Keyboard shortcuts when [includesShortcuts]; then Close book apart.
     * [includesSeries] is false where the screen already shows the series as a link (the native wide layouts).
     * [includesShortcuts] is true only where the platform has app keyboard shortcuts (the web). Pure.
     */
    fun moreMenu(
        state: PlayerUiState.Loaded,
        includesSeries: Boolean,
        includesShortcuts: Boolean,
    ): List<PlayerMenuGroup> {
        val book = state.book
        val places =
            buildList {
                add(PlayerMenuItem.GoToBook)
                book.series
                    .firstOrNull()
                    ?.takeIf { includesSeries }
                    ?.let { add(PlayerMenuItem.GoToSeries(it.id)) }
                book.firstPdfDocId?.let { add(PlayerMenuItem.OpenPdf(it)) }
                add(PlayerMenuItem.VolumeBoost(state.settings.boostDb))
                if (includesShortcuts) add(PlayerMenuItem.KeyboardShortcuts)
            }
        return listOf(
            PlayerMenuGroup(places),
            PlayerMenuGroup(listOf(PlayerMenuItem.CloseBook), isDestructive = true),
        )
    }
}
