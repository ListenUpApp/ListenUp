package com.calypsan.listenup.web.features.admin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.presentation.admin.AdminCategoriesUiState
import com.calypsan.listenup.client.presentation.admin.GenreTreeNode
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
 * Categories — the genre tree, and the five things an admin does to it.
 *
 * Pure in [state]; the store wiring lives one level up. Which dialog is open is view-local, as a
 * single [CategoryDialog] rather than five flags: nothing has been asked of the server until one is
 * confirmed, and `showModal()` already makes two-at-once unreachable.
 *
 * **The tree is flattened here, not nested.** `GenreTreeNode` carries its own `depth`, and rendering
 * one flat list of rows indented by that depth is what lets every row be a sibling in the DOM —
 * which is what keeps keyboard order equal to reading order. Nested `<div>`s would put a child's
 * row inside its parent's row, and a parent's action buttons would then be in the tab order of
 * every descendant.
 *
 * A collapsed genre's children are absent from the DOM rather than hidden with CSS: a tree with
 * 300 genres in it should not put 300 rows in the accessibility tree to show eight.
 */
@Composable
fun CategoriesPage(
    state: AdminCategoriesUiState,
    onToggleExpanded: (String) -> Unit,
    onExpandAll: () -> Unit,
    onCollapseAll: () -> Unit,
    onCreate: (name: String, parentId: String?) -> Unit,
    onRename: (id: String, name: String) -> Unit,
    onDelete: (String) -> Unit,
    onMove: (id: String, newParentId: String?) -> Unit,
    onMerge: (source: String, target: String) -> Unit,
    onClearError: () -> Unit,
    onOpenAdmin: () -> Unit,
) {
    Div(attrs = { classes("cat") }) {
        Button(attrs = {
            classes("btn-o", "cat-back")
            attr("type", VALUE_BUTTON)
            onClick { onOpenAdmin() }
        }) { Text("← Admin") }

        H1(attrs = { classes("cat-title") }) { Text("Categories") }

        when (state) {
            AdminCategoriesUiState.Loading -> {
                Div(attrs = { classes("skel", "cat-skel") })
            }

            is AdminCategoriesUiState.Error -> {
                Div(attrs = { classes("empty") }) {
                    H3 { Text("Categories can't be shown") }
                    P { Text(state.error.message) }
                }
            }

            is AdminCategoriesUiState.Ready -> {
                ReadyContent(
                    state = state,
                    onToggleExpanded = onToggleExpanded,
                    onExpandAll = onExpandAll,
                    onCollapseAll = onCollapseAll,
                    onCreate = onCreate,
                    onRename = onRename,
                    onDelete = onDelete,
                    onMove = onMove,
                    onMerge = onMerge,
                    onClearError = onClearError,
                )
            }
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun ReadyContent(
    state: AdminCategoriesUiState.Ready,
    onToggleExpanded: (String) -> Unit,
    onExpandAll: () -> Unit,
    onCollapseAll: () -> Unit,
    onCreate: (String, String?) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onMove: (String, String?) -> Unit,
    onMerge: (String, String) -> Unit,
    onClearError: () -> Unit,
) {
    var dialog by remember { mutableStateOf<CategoryDialog?>(null) }
    val close = { dialog = null }

    state.error?.let { error ->
        Div(attrs = { classes("cat-err") }) {
            P(attrs = {
                classes("cat-err-t")
                attr("role", "alert")
            }) { Text(error.message) }
            Button(attrs = {
                classes("cat-err-x")
                attr("type", VALUE_BUTTON)
                attr("aria-label", "Dismiss")
                onClick { onClearError() }
            }) { Icon(WebIcon.X, size = SMALL_ICON) }
        }
    }

    Div(attrs = { classes("cat-bar") }) {
        Span(attrs = { classes("cat-count") }) { Text(genreSummary(state.genres.size, state.totalBookCount)) }
        Button(attrs = {
            classes("btn-o", "cat-bar-b")
            attr("type", VALUE_BUTTON)
            onClick { onExpandAll() }
        }) { Text("Expand all") }
        Button(attrs = {
            classes("btn-o", "cat-bar-b")
            attr("type", VALUE_BUTTON)
            onClick { onCollapseAll() }
        }) { Text("Collapse all") }
        Button(attrs = {
            classes("btn-c", "cat-bar-b")
            attr("type", VALUE_BUTTON)
            disabledWhen(state.isSaving)
            onClick { dialog = CategoryDialog.Create(parent = null) }
        }) { Text("New genre") }
    }

    if (state.tree.isEmpty()) {
        Div(attrs = { classes("empty") }) {
            H3 { Text("No genres yet") }
            P { Text("Genres arrive with your books, and you can add your own here to group them.") }
        }
    } else {
        Div(attrs = {
            classes("cat-tree")
            attr("role", "tree")
        }) {
            state.tree.forEach { node ->
                GenreRows(
                    node = node,
                    expandedIds = state.expandedIds,
                    isSaving = state.isSaving,
                    onToggleExpanded = onToggleExpanded,
                    onAct = { dialog = it },
                )
            }
        }
    }

    when (val open = dialog) {
        null -> {
            Unit
        }

        is CategoryDialog.Create -> {
            NameDialog(
                title = open.parent?.let { "New genre under ${it.name}" } ?: "New genre",
                confirmLabel = "Create",
                initialName = "",
                hint = null,
                onConfirm = { name ->
                    close()
                    onCreate(name, open.parent?.id)
                },
                onDismiss = close,
            )
        }

        is CategoryDialog.Rename -> {
            NameDialog(
                title = "Rename ${open.genre.name}",
                confirmLabel = "Rename",
                initialName = open.genre.name,
                hint = null,
                onConfirm = { name ->
                    close()
                    onRename(open.genre.id, name)
                },
                onDismiss = close,
            )
        }

        is CategoryDialog.Delete -> {
            ConfirmDialog(
                open = true,
                title = "Delete ${open.genre.name}?",
                // The count is the whole decision: deleting an empty genre is housekeeping,
                // deleting one with 400 books in it is not.
                body = deleteWarning(open.genre),
                confirmLabel = "Delete",
                onConfirm = {
                    close()
                    onDelete(open.genre.id)
                },
                onDismiss = close,
            )
        }

        is CategoryDialog.Move -> {
            MoveDialog(
                genre = open.genre,
                all = state.genres,
                onConfirm = { parentId ->
                    close()
                    onMove(open.genre.id, parentId)
                },
                onDismiss = close,
            )
        }

        is CategoryDialog.Merge -> {
            MergeDialog(
                genre = open.genre,
                all = state.genres,
                onConfirm = { targetId ->
                    close()
                    onMerge(open.genre.id, targetId)
                },
                onDismiss = close,
            )
        }
    }
}

/**
 * One genre's row, then its children's — flat, so every row is a sibling in the DOM.
 *
 * Recursion produces the list; it does not produce the nesting. See the page KDoc for why.
 */
@Composable
private fun GenreRows(
    node: GenreTreeNode,
    expandedIds: Set<String>,
    isSaving: Boolean,
    onToggleExpanded: (String) -> Unit,
    onAct: (CategoryDialog) -> Unit,
) {
    val expanded = node.genre.id in expandedIds
    GenreRow(
        node = node,
        expanded = expanded,
        isSaving = isSaving,
        onToggleExpanded = onToggleExpanded,
        onAct = onAct,
    )
    if (expanded) {
        node.children.forEach { child ->
            GenreRows(child, expandedIds, isSaving, onToggleExpanded, onAct)
        }
    }
}

@Composable
private fun GenreRow(
    node: GenreTreeNode,
    expanded: Boolean,
    isSaving: Boolean,
    onToggleExpanded: (String) -> Unit,
    onAct: (CategoryDialog) -> Unit,
) {
    val genre = node.genre
    val hasChildren = node.children.isNotEmpty()

    Div(attrs = {
        classes("cat-row")
        attr("role", "treeitem")
        attr("aria-level", (node.depth + 1).toString())
        // Only a genre that HAS children can be expanded, and a leaf must not claim otherwise —
        // `aria-expanded` on a leaf tells a screen reader there is something to open.
        if (hasChildren) attr("aria-expanded", expanded.toString())
        // Indent by depth. A style rather than a class per level: the tree has no fixed maximum
        // depth, so a class per level is a set of rules nobody can finish writing.
        style { property("padding-left", "${node.depth * INDENT_PX}px") }
    }) {
        if (hasChildren) {
            Button(attrs = {
                classes("cat-twist")
                attr("type", VALUE_BUTTON)
                attr("aria-label", if (expanded) "Collapse ${genre.name}" else "Expand ${genre.name}")
                onClick { onToggleExpanded(genre.id) }
            }) { Icon(if (expanded) WebIcon.ChevronDown else WebIcon.ChevronRight, size = SMALL_ICON) }
        } else {
            // Holds the twisty's width so leaf names line up with their siblings' rather than
            // shifting left by exactly one control.
            Div(attrs = { classes("cat-twist-gap") })
        }

        Span(attrs = { classes("cat-name") }) { Text(genre.name) }
        Span(attrs = { classes("cat-books") }) { Text(bookCountLabel(genre.bookCount)) }

        Div(attrs = { classes("cat-acts") }) {
            RowAction("Add a genre under ${genre.name}", WebIcon.Plus, isSaving) {
                onAct(CategoryDialog.Create(parent = genre))
            }
            RowAction("Rename ${genre.name}", WebIcon.Pencil, isSaving) {
                onAct(CategoryDialog.Rename(genre))
            }
            RowAction("Move ${genre.name}", WebIcon.ArrowRight, isSaving) {
                onAct(CategoryDialog.Move(genre))
            }
            RowAction("Merge ${genre.name} into another genre", WebIcon.Merge, isSaving) {
                onAct(CategoryDialog.Merge(genre))
            }
            RowAction("Delete ${genre.name}", WebIcon.Trash, isSaving) {
                onAct(CategoryDialog.Delete(genre))
            }
        }
    }
}

/**
 * One icon button in a row's action strip.
 *
 * The label is the genre's name and the verb, not the verb alone: five identical "Rename" buttons
 * down a tree is what a screen reader would otherwise announce, with nothing to tell them apart.
 */
@Composable
private fun RowAction(
    label: String,
    icon: WebIcon,
    isSaving: Boolean,
    onClick: () -> Unit,
) {
    Button(attrs = {
        classes("iconbtn", "cat-act")
        attr("type", VALUE_BUTTON)
        attr("aria-label", label)
        attr("title", label)
        disabledWhen(isSaving)
        onClick { onClick() }
    }) { Icon(icon, size = SMALL_ICON) }
}

/** What deleting this genre costs, in the only terms that matter. */
private fun deleteWarning(genre: Genre): String =
    if (genre.bookCount == 0) {
        "${genre.name} has no books in it. Nothing on disk is deleted."
    } else {
        "${genre.name} has ${bookCountLabel(genre.bookCount)} in it. They stay in your library and " +
            "lose this genre. Nothing on disk is deleted."
    }

private fun bookCountLabel(count: Int): String = if (count == 1) "1 book" else "$count books"

private fun genreSummary(
    genres: Int,
    books: Int,
): String {
    val genreText = if (genres == 1) "1 genre" else "$genres genres"
    return "$genreText · ${bookCountLabel(books)}"
}

/** Every button here is an action, never a form submit. */
private const val VALUE_BUTTON = "button"

private const val SMALL_ICON = 16

/** One step of indentation per level of the tree. */
private const val INDENT_PX = 22
