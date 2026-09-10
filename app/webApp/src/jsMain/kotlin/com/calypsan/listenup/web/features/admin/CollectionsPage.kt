package com.calypsan.listenup.web.features.admin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.presentation.admin.AdminCollectionsUiState
import com.calypsan.listenup.web.design.ConfirmDialog
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.disabledWhen
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Collections — the groups an admin curates, and who can be given one.
 *
 * Pure in [state]; the store wiring lives one level up.
 *
 * ⛔ **A system collection is not an ordinary one.** The library's own inbox and its all-books
 * collection are created by the server and referred to by name elsewhere in it — renaming or
 * deleting one is a request the server refuses. Compose locks both; so does this. The lock is shown
 * rather than the buttons hidden: a row that is simply missing its Delete reads as a rendering
 * fault, where a padlock reads as a decision someone made.
 */
@Composable
fun CollectionsPage(
    state: AdminCollectionsUiState,
    onCreate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClearError: () -> Unit,
    onOpenCollection: (String) -> Unit,
    onOpenAdmin: () -> Unit,
) {
    Div(attrs = { classes("coll") }) {
        Button(attrs = {
            classes("btn-o", "coll-back")
            attr("type", VALUE_BUTTON)
            onClick { onOpenAdmin() }
        }) { Text("← Admin") }

        H1(attrs = { classes("coll-title") }) { Text("Collections") }

        when (state) {
            AdminCollectionsUiState.Loading -> {
                Div(attrs = { classes("skel", "coll-skel") })
            }

            is AdminCollectionsUiState.Error -> {
                Div(attrs = { classes("empty") }) {
                    H3 { Text("Collections can't be shown") }
                    P { Text(state.message) }
                }
            }

            is AdminCollectionsUiState.Ready -> {
                ReadyContent(state, onCreate, onDelete, onClearError, onOpenCollection)
            }
        }
    }
}

@Composable
private fun ReadyContent(
    state: AdminCollectionsUiState.Ready,
    onCreate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClearError: () -> Unit,
    onOpenCollection: (String) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Collection?>(null) }

    state.error?.let { message ->
        CollectionNotice(message, onClearError)
    }

    Div(attrs = { classes("coll-bar") }) {
        Span(attrs = { classes("coll-count") }) { Text(collectionSummary(state.collections.size)) }
        Button(attrs = {
            classes("btn-c", "coll-new")
            attr("type", VALUE_BUTTON)
            disabledWhen(state.isCreating)
            onClick { creating = true }
        }) { Text(if (state.isCreating) "Creating…" else "New collection") }
    }

    if (state.collections.isEmpty()) {
        Div(attrs = { classes("empty") }) {
            H3 { Text("No collections yet") }
            P { Text("A collection is a group of books you can hand to one person, or to everyone.") }
        }
    } else {
        Div(attrs = { classes("coll-list") }) {
            state.collections.forEach { collection ->
                CollectionRow(
                    collection = collection,
                    isDeleting = state.deletingCollectionId == collection.id,
                    onOpen = { onOpenCollection(collection.id) },
                    onAskDelete = { pendingDelete = collection },
                )
            }
        }
    }

    if (creating) {
        NameDialog(
            title = "New collection",
            confirmLabel = "Create",
            initialName = "",
            hint = "Books you add to it can be shared with anyone on this server.",
            onConfirm = { name ->
                creating = false
                onCreate(name)
            },
            onDismiss = { creating = false },
        )
    }

    val pending = pendingDelete
    ConfirmDialog(
        open = pending != null,
        title = "Delete this collection?",
        // The books survive; only the grouping goes. Saying so is what stops this reading as
        // "delete these 40 books".
        body =
            "${pending?.name ?: "This collection"} will be removed, and anyone it was shared with " +
                "will stop seeing it. The books stay in the library.",
        confirmLabel = "Delete",
        onConfirm = {
            pending?.let { onDelete(it.id) }
            pendingDelete = null
        },
        onDismiss = { pendingDelete = null },
    )
}

@Composable
private fun CollectionRow(
    collection: Collection,
    isDeleting: Boolean,
    onOpen: () -> Unit,
    onAskDelete: () -> Unit,
) {
    Div(attrs = { classes("coll-row") }) {
        // The name opens the collection; the trailing control deletes it. Two hit targets rather
        // than one button holding another, which is not a thing HTML allows.
        Button(attrs = {
            classes("coll-open")
            attr("type", VALUE_BUTTON)
            onClick { onOpen() }
        }) {
            Span(attrs = { classes("coll-name") }) { Text(collection.name) }
            Span(attrs = { classes("coll-books") }) { Text(bookCount(collection.bookCount)) }
        }
        if (collection.isSystem) {
            Span(attrs = {
                classes("coll-lock")
                attr("title", "The server manages this collection")
            }) {
                Icon(WebIcon.Lock, size = SMALL_ICON)
                Span(attrs = { classes("coll-lock-t") }) { Text("Managed") }
            }
        } else {
            Button(attrs = {
                classes("iconbtn", "coll-del")
                attr("type", VALUE_BUTTON)
                attr("aria-label", "Delete ${collection.name}")
                attr("title", "Delete ${collection.name}")
                disabledWhen(isDeleting)
                onClick { onAskDelete() }
            }) { Icon(WebIcon.Trash, size = SMALL_ICON) }
        }
    }
}

/** A dismissible line above the list: something the last action failed to do. */
@Composable
internal fun CollectionNotice(
    message: String,
    onDismiss: () -> Unit,
) {
    Div(attrs = { classes("coll-err") }) {
        P(attrs = {
            classes("coll-err-t")
            attr("role", "alert")
        }) { Text(message) }
        Button(attrs = {
            classes("coll-err-x")
            attr("type", VALUE_BUTTON)
            attr("aria-label", "Dismiss")
            onClick { onDismiss() }
        }) { Icon(WebIcon.X, size = SMALL_ICON) }
    }
}

internal fun bookCount(count: Int): String = if (count == 1) "1 book" else "$count books"

private fun collectionSummary(count: Int): String = if (count == 1) "1 collection" else "$count collections"

/** Every button here is an action, never a form submit. */
private const val VALUE_BUTTON = "button"

private const val SMALL_ICON = 16
