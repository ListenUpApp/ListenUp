package com.calypsan.listenup.client.presentation.bookdetail

/** One command in Book Detail's More menu (spec §2.7). Each platform supplies its words, its glyph and the action. */
sealed interface BookDetailMenuItem {
    /** Share a link to the book. */
    data object Share : BookDetailMenuItem

    /** Mark the book finished; the platform asks for the days first. */
    data object MarkFinished : BookDetailMenuItem

    /** Send the position back to the start; the platform asks first. */
    data object Restart : BookDetailMenuItem

    /** Clear the progress entirely; the platform asks first. */
    data object MarkNotStarted : BookDetailMenuItem

    /** Open the book editor. */
    data object EditDetails : BookDetailMenuItem

    /** Open Match details. */
    data object MatchDetails : BookDetailMenuItem

    /** Open the chapter editor. */
    data object EditChapters : BookDetailMenuItem

    /** Open the collection picker. Admin only: only admins write collections. */
    data object AddToCollection : BookDetailMenuItem

    /** Delete the book's folder from the server, for every member and device. Admin only; the platform asks first. */
    data object DeleteFromServer : BookDetailMenuItem
}

/** The heading over a group of Book Detail's More menu. The words are each platform's. */
sealed interface BookDetailMenuHeading {
    /** Mark as finished, Restart, Mark as not started. */
    data object Progress : BookDetailMenuHeading

    /** Edit details, Match details, Edit chapters. */
    data object Edit : BookDetailMenuHeading

    /** Add to collection. */
    data object Admin : BookDetailMenuHeading
}

/**
 * One group of Book Detail's More menu, drawn after a divider from the one before it.
 *
 * @property heading The group's heading; null for the leading Share group and for the Delete group.
 * @property items The group's commands, in order. Never empty: an empty group is left out.
 * @property isDestructive True only for the Delete group, which platforms draw in the error colour or destructive role.
 */
data class BookDetailMenuGroup(
    val heading: BookDetailMenuHeading?,
    val items: List<BookDetailMenuItem>,
    val isDestructive: Boolean = false,
)

/**
 * Book Detail's More menu for [ready] (spec §2.7): Share; Progress; Edit (with Edit metadata); Admin; then Delete alone
 * (admins). Empty groups are left out, and a held book has no menu at all (its triage panel replaces it). Add to shelf
 * is never here: it is a visible action. Pure.
 */
fun bookDetailMoreMenu(ready: BookDetailUiState.Ready): List<BookDetailMenuGroup> {
    if (ready.isHeld) return emptyList()
    val progress =
        buildList {
            if (ready.readingStage != ReadingStage.Finished) add(BookDetailMenuItem.MarkFinished)
            if (ready.readingStage != ReadingStage.NotStarted) {
                add(BookDetailMenuItem.Restart)
                add(BookDetailMenuItem.MarkNotStarted)
            }
        }
    val edit =
        if (ready.canEditMetadata) {
            listOf(BookDetailMenuItem.EditDetails, BookDetailMenuItem.MatchDetails, BookDetailMenuItem.EditChapters)
        } else {
            emptyList()
        }
    val admin = if (ready.isAdmin) listOf(BookDetailMenuItem.AddToCollection) else emptyList()
    val delete = if (ready.isAdmin) listOf(BookDetailMenuItem.DeleteFromServer) else emptyList()
    return listOf(
        BookDetailMenuGroup(heading = null, items = listOf(BookDetailMenuItem.Share)),
        BookDetailMenuGroup(BookDetailMenuHeading.Progress, progress),
        BookDetailMenuGroup(BookDetailMenuHeading.Edit, edit),
        BookDetailMenuGroup(BookDetailMenuHeading.Admin, admin),
        BookDetailMenuGroup(heading = null, items = delete, isDestructive = true),
    ).filter { it.items.isNotEmpty() }
}
