package com.calypsan.listenup.web.features.admin

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.CollectionBookItem
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.presentation.admin.AdminCollectionDetailUiState
import com.calypsan.listenup.client.presentation.admin.CollectionShareItem
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.DialogActions
import com.calypsan.listenup.web.design.DialogText
import com.calypsan.listenup.web.design.Field
import com.calypsan.listenup.web.design.FormSection
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.ModalDialog
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.coverUrl
import com.calypsan.listenup.web.design.disabledWhen
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * One collection: its name, the books in it, and the people who can see it.
 *
 * Pure in [state]; the store wiring lives one level up. Both panels the page can open — the book
 * search and the member picker — are ViewModel state rather than view-local, because each one loads
 * something when it opens and the ViewModel is what does the loading.
 *
 * ⛔ **A system collection is read-only here.** The library's inbox and its all-books collection are
 * the server's, and it refuses to rename them or change what is in them. The name field is disabled
 * and says why, and Add books is absent — the same three guards Compose applies. Offering controls
 * that the server will reject is worse than not offering them: the reader learns the app lies.
 */
@Composable
fun CollectionDetailPage(
    state: AdminCollectionDetailUiState,
    onNameChange: (String) -> Unit,
    onSaveName: () -> Unit,
    onRemoveBook: (String) -> Unit,
    onOpenAddBooks: () -> Unit,
    onCloseAddBooks: () -> Unit,
    onBookQuery: (String) -> Unit,
    onAddBook: (String) -> Unit,
    onShowAddMember: () -> Unit,
    onHideAddMember: () -> Unit,
    onShare: (String) -> Unit,
    onRevokeShare: (String) -> Unit,
    onClearError: () -> Unit,
    onOpenCollections: () -> Unit,
) {
    Div(attrs = { classes("cdet") }) {
        Button(attrs = {
            classes("btn-o", "cdet-back")
            attr("type", VALUE_BUTTON)
            onClick { onOpenCollections() }
        }) { Text("← Collections") }

        when (state) {
            AdminCollectionDetailUiState.Loading -> {
                Div(attrs = { classes("skel", "cdet-skel") })
            }

            is AdminCollectionDetailUiState.Error -> {
                Div(attrs = { classes("empty") }) {
                    H3 { Text("This collection can't be shown") }
                    P { Text(state.message) }
                }
            }

            is AdminCollectionDetailUiState.Ready -> {
                ReadyContent(
                    state = state,
                    onNameChange = onNameChange,
                    onSaveName = onSaveName,
                    onRemoveBook = onRemoveBook,
                    onOpenAddBooks = onOpenAddBooks,
                    onCloseAddBooks = onCloseAddBooks,
                    onBookQuery = onBookQuery,
                    onAddBook = onAddBook,
                    onShowAddMember = onShowAddMember,
                    onHideAddMember = onHideAddMember,
                    onShare = onShare,
                    onRevokeShare = onRevokeShare,
                    onClearError = onClearError,
                )
            }
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun ReadyContent(
    state: AdminCollectionDetailUiState.Ready,
    onNameChange: (String) -> Unit,
    onSaveName: () -> Unit,
    onRemoveBook: (String) -> Unit,
    onOpenAddBooks: () -> Unit,
    onCloseAddBooks: () -> Unit,
    onBookQuery: (String) -> Unit,
    onAddBook: (String) -> Unit,
    onShowAddMember: () -> Unit,
    onHideAddMember: () -> Unit,
    onShare: (String) -> Unit,
    onRevokeShare: (String) -> Unit,
    onClearError: () -> Unit,
) {
    val managed = state.collection.isSystem

    H1(attrs = { classes("cdet-title") }) { Text(state.collection.name) }

    state.error?.let { message -> CollectionNotice(message, onClearError) }

    FormSection(title = "Name") {
        Field(
            label = "Collection name",
            value = state.editedName,
            onInput = onNameChange,
            id = "cdet-name",
            enabled = !managed,
        )
        P(attrs = { classes("cdet-hint") }) {
            Text(
                if (managed) {
                    "The server manages this collection, so its name is fixed."
                } else {
                    "What this collection is called, everywhere it appears."
                },
            )
        }
        // Absent rather than disabled while there is nothing to save: a Save button that is
        // permanently greyed on a screen you did not come here to edit is just furniture.
        if (state.isDirty && !managed) {
            Div(attrs = { classes("edit-actions") }) {
                Button(attrs = {
                    classes("btn-c")
                    attr("type", VALUE_BUTTON)
                    disabledWhen(state.isSaving)
                    onClick { onSaveName() }
                }) { Text(if (state.isSaving) "Saving…" else "Save changes") }
            }
        }
    }

    FormSection(title = "Books") {
        if (!managed) {
            Div(attrs = { classes("cdet-sec-act") }) {
                Button(attrs = {
                    classes("btn-o", "cdet-add")
                    attr("type", VALUE_BUTTON)
                    onClick { onOpenAddBooks() }
                }) { Text("Add books") }
            }
        }
        if (state.books.isEmpty()) {
            Nothing("Nothing in this collection yet.")
        } else {
            Div(attrs = { classes("cdet-books") }) {
                state.books.forEach { book ->
                    BookRow(
                        book = book,
                        isRemoving = state.removingBookId == book.id,
                        canRemove = !managed,
                        onRemove = { onRemoveBook(book.id) },
                    )
                }
            }
        }
    }

    FormSection(title = "Shared with") {
        Div(attrs = { classes("cdet-sec-act") }) {
            Button(attrs = {
                classes("btn-o", "cdet-share")
                attr("type", VALUE_BUTTON)
                disabledWhen(state.isSharing)
                onClick { onShowAddMember() }
            }) { Text("Share with someone") }
        }
        if (state.shares.isEmpty()) {
            // Not "nobody can see this": the owner always can, and the all-books collection is
            // visible to everyone without a share row. Saying only what is true.
            Nothing("Not shared with anyone yet.")
        } else {
            Div(attrs = { classes("cdet-shares") }) {
                state.shares.forEach { share ->
                    ShareRow(
                        share = share,
                        isRemoving = state.removingShareUserId == share.userId,
                        onRevoke = { onRevokeShare(share.userId) },
                    )
                }
            }
        }
    }

    if (state.showAddBooks) {
        AddBooksDialog(
            query = state.bookQuery,
            results = state.bookResults,
            isSearching = state.isSearchingBooks,
            onQuery = onBookQuery,
            onAdd = onAddBook,
            onDismiss = onCloseAddBooks,
        )
    }

    if (state.showAddMemberSheet) {
        AddMemberDialog(
            users = state.availableUsers,
            shares = state.shares,
            isLoading = state.isLoadingUsers,
            onShare = onShare,
            onDismiss = onHideAddMember,
        )
    }
}

@Composable
private fun BookRow(
    book: CollectionBookItem,
    isRemoving: Boolean,
    canRemove: Boolean,
    onRemove: () -> Unit,
) {
    Div(attrs = { classes("cdet-book") }) {
        Cover(
            title = book.title,
            imageUrl = coverUrl(book.id, book.coverHash, width = COVER_RUNG),
            size = COVER_SIZE,
            radius = COVER_RADIUS,
        )
        Div(attrs = { classes("cdet-book-t") }) {
            Span(attrs = { classes("cdet-book-title") }) { Text(book.title) }
            book.author?.takeIf { it.isNotBlank() }?.let { author ->
                Span(attrs = { classes("cdet-book-by") }) { Text(author) }
            }
        }
        if (canRemove) {
            Button(attrs = {
                classes("iconbtn", "cdet-x")
                attr("type", VALUE_BUTTON)
                attr("aria-label", "Remove ${book.title} from this collection")
                attr("title", "Remove from this collection")
                disabledWhen(isRemoving)
                onClick { onRemove() }
            }) { Icon(WebIcon.X, size = SMALL_ICON) }
        }
    }
}

@Composable
private fun ShareRow(
    share: CollectionShareItem,
    isRemoving: Boolean,
    onRevoke: () -> Unit,
) {
    Div(attrs = { classes("cdet-share-row") }) {
        Span(attrs = { classes("cdet-share-n") }) { Text(share.displayName) }
        Span(attrs = { classes("cdet-share-p") }) { Text(share.permission.lowercase()) }
        Button(attrs = {
            classes("iconbtn", "cdet-x")
            attr("type", VALUE_BUTTON)
            attr("aria-label", "Stop sharing with ${share.displayName}")
            attr("title", "Stop sharing")
            disabledWhen(isRemoving)
            onClick { onRevoke() }
        }) { Icon(WebIcon.X, size = SMALL_ICON) }
    }
}

/**
 * Search the library and add what you find.
 *
 * Typing is the only trigger: the ViewModel debounces and searches, so there is no Search button to
 * press and nothing here decides when a query is worth running.
 */
@Composable
private fun AddBooksDialog(
    query: String,
    results: List<SearchHit>,
    isSearching: Boolean,
    onQuery: (String) -> Unit,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalDialog(open = true, title = "Add books", onDismiss = onDismiss) {
        Field(
            label = "Search the library",
            value = query,
            onInput = onQuery,
            id = "cdet-book-query",
        )
        Div(attrs = { classes("cdet-results") }) {
            when {
                isSearching -> {
                    Nothing("Searching…")
                }

                // Two different nothings: nothing typed yet, and nothing found. A reader who has
                // typed a title needs to know the search ran and came back empty.
                query.isBlank() -> {
                    Nothing("Type a title or an author.")
                }

                results.isEmpty() -> {
                    Nothing("Nothing matched \"$query\".")
                }

                else -> {
                    results.forEach { hit ->
                        Button(attrs = {
                            classes("cdet-result")
                            attr("type", VALUE_BUTTON)
                            onClick { onAdd(hit.id) }
                        }) {
                            Span(attrs = { classes("cdet-result-t") }) { Text(hit.name) }
                            Icon(WebIcon.Plus, size = SMALL_ICON)
                        }
                    }
                }
            }
        }
        Div(attrs = { classes("dlg-actions") }) {
            // No confirm: each result adds on click, and the dialog stays open so several books
            // can go in without reopening it. Done is the only way out, so it is the only button.
            Button(attrs = {
                classes("btn")
                attr("type", VALUE_BUTTON)
                onClick { onDismiss() }
            }) { Text("Done") }
        }
    }
}

/** Who else can see this collection. Anyone already shared with is not offered twice. */
@Composable
private fun AddMemberDialog(
    users: List<AdminUserInfo>,
    shares: List<CollectionShareItem>,
    isLoading: Boolean,
    onShare: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val alreadyShared = shares.map { it.userId }.toSet()
    val offerable = users.filterNot { it.id in alreadyShared }

    ModalDialog(open = true, title = "Share with someone", onDismiss = onDismiss) {
        when {
            isLoading -> {
                DialogText("Loading people…")
            }

            offerable.isEmpty() -> {
                DialogText(
                    if (users.isEmpty()) {
                        "There is nobody else on this server yet."
                    } else {
                        "Everyone on this server already has this collection."
                    },
                )
            }

            else -> {
                Div(attrs = { classes("cdet-people") }) {
                    offerable.forEach { user ->
                        Button(attrs = {
                            classes("cdet-person")
                            attr("type", VALUE_BUTTON)
                            onClick { onShare(user.id) }
                        }) {
                            Span(attrs = { classes("cdet-person-n") }) { Text(user.displayName ?: user.email) }
                            Icon(WebIcon.Plus, size = SMALL_ICON)
                        }
                    }
                }
            }
        }
        DialogActions(confirmLabel = "Done", onConfirm = onDismiss, onDismiss = onDismiss)
    }
}

/** A line saying there is nothing here — which of the several nothings is the caller's to say. */
@Composable
private fun Nothing(text: String) {
    P(attrs = { classes("cdet-none") }) { Text(text) }
}

private const val VALUE_BUTTON = "button"

private const val SMALL_ICON = 16

private const val COVER_SIZE = 48

/** 2× the rendered width, so the derivative the server picks stays sharp on dense displays. */
private const val COVER_RUNG = 96

private const val COVER_RADIUS = 6
