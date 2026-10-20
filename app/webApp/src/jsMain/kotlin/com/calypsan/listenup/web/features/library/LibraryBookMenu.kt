package com.calypsan.listenup.web.features.library

import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.presentation.library.BookMenuPrimary
import com.calypsan.listenup.web.design.MenuAction
import com.calypsan.listenup.web.design.WebIcon
import kotlin.time.Duration.Companion.milliseconds

/**
 * What a Library book's menu can do. A null action drops its item rather than disabling it: Share without a server
 * that can name itself, Add to shelf while a selection is under way, and the admin pair for a member.
 */
internal class LibraryBookMenuActions(
    val onPrimary: () -> Unit,
    val onAddToShelf: (() -> Unit)?,
    val onShare: (() -> Unit)?,
    val onSelect: () -> Unit,
    val onAddToCollection: (() -> Unit)?,
    val onEditDetails: (() -> Unit)?,
)

/**
 * The page's side of every card's menu: whether the reader is an admin, and what each item does by book id. The route
 * builds it from the playback, share, selection and router it owns; the page only asks it per book.
 */
class LibraryBookMenuSource(
    val isAdmin: Boolean,
    val onPlay: (String) -> Unit,
    val onShare: ((bookId: String, title: String) -> Unit)?,
    val onSelectOne: (String) -> Unit,
    val onShelveOne: ((String) -> Unit)?,
    val onCollectOne: ((String) -> Unit)?,
    val onEdit: ((String) -> Unit)?,
)

/**
 * A Library book's menu (board `Library-WebDesktop-ContextMenu`): the primary first — "Play", "Resume · 6h 2m left" or
 * "Listen again" — then Add to shelf…, Share… and Select; for an admin, an "Admin" group of Add to collection… and
 * Edit details, last. The "…" items open a picker or a sheet.
 */
internal fun libraryBookMenuItems(
    primary: BookMenuPrimary,
    isAdmin: Boolean,
    actions: LibraryBookMenuActions,
): List<MenuAction> =
    buildList {
        add(MenuAction(primaryLabel(primary), WebIcon.Play, actions.onPrimary))
        actions.onAddToShelf?.let { add(MenuAction("Add to shelf…", WebIcon.Bookmark, it)) }
        actions.onShare?.let { add(MenuAction("Share…", WebIcon.Share, it)) }
        add(MenuAction("Select", WebIcon.Check, actions.onSelect))
        if (isAdmin) {
            actions.onAddToCollection?.let { add(MenuAction("Add to collection…", WebIcon.Layers, it, group = ADMIN_GROUP)) }
            actions.onEditDetails?.let { add(MenuAction("Edit details", WebIcon.Pencil, it, group = ADMIN_GROUP)) }
        }
    }

private fun primaryLabel(primary: BookMenuPrimary): String =
    when (primary) {
        BookMenuPrimary.Play -> "Play"
        BookMenuPrimary.ListenAgain -> "Listen again"
        is BookMenuPrimary.Resume -> "Resume · ${DurationFormatter.hoursMinutes(primary.timeLeftMs.milliseconds)} left"
    }

private const val ADMIN_GROUP = "Admin"
