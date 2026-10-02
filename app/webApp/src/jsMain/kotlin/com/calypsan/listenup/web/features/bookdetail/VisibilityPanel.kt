package com.calypsan.listenup.web.features.bookdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.domain.model.BookVisibility
import com.calypsan.listenup.client.domain.model.CollectionRef
import com.calypsan.listenup.client.domain.model.HiddenFrom
import com.calypsan.listenup.client.presentation.bookdetail.HiddenFromNames
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.Panel
import com.calypsan.listenup.web.design.Pill
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Book Detail's Visibility panel — the side column's first, above Details. Says who cannot see the
 * book, then the collections that are the reason, each a pill that opens its collection. Admins
 * only ("Admins only" in the header, a note rather than a call to act); renders only for
 * [BookVisibility.Restricted] and [BookVisibility.Stranded]. The copy matches Android and iOS word
 * for word (web has no shared catalog).
 */
@Composable
internal fun VisibilityPanel(
    bookId: String,
    visibility: BookVisibility,
    isRestoring: Boolean,
    onOpenCollection: (String) -> Unit,
    onRestoreToAllBooks: () -> Unit,
    onAddToCollection: () -> Unit,
) {
    if (visibility !is BookVisibility.Restricted && visibility != BookVisibility.Stranded) return
    // The wrapper carries the panel's name for its styles and specs; `Panel` itself takes no attrs.
    Div(attrs = { classes("bd-vis") }) {
        Panel(title = "Visibility", trailing = {
            Span(attrs = { classes("bd-vis-note") }) {
                Icon(WebIcon.Lock, size = NOTE_ICON)
                Text("Admins only")
            }
        }) {
            when (visibility) {
                is BookVisibility.Restricted -> Restricted(bookId, visibility, onOpenCollection)
                else -> Stranded(isRestoring, onRestoreToAllBooks, onAddToCollection)
            }
        }
    }
}

@Composable
private fun Restricted(
    bookId: String,
    visibility: BookVisibility.Restricted,
    onOpenCollection: (String) -> Unit,
) {
    // Per book visit: a long list starts collapsed again on the next book.
    var expanded by remember(bookId) { mutableStateOf(false) }
    val hiddenFrom = visibility.hiddenFrom
    val collections = visibility.collections
    Div(attrs = { classes("bd-vis-body") }) {
        Div(attrs = { classes("bd-vis-says") }) {
            P(attrs = { classes("bd-vis-head") }) { Text(headline(hiddenFrom, expanded)) }
            P(attrs = { classes("bd-vis-why") }) { Text(reason(hiddenFrom, collections)) }
            if (hiddenFrom is HiddenFrom.Members && HiddenFromNames.canExpand(hiddenFrom.names, expanded)) {
                Div(attrs = { classes("bd-vis-more") }) {
                    Button(kind = ButtonKind.Ghost, onClick = { expanded = true }) {
                        Text("Show all ${hiddenFrom.names.size}")
                    }
                }
            }
        }
        Div(attrs = { classes("bd-vis-in") }) {
            Span(attrs = { classes("bd-vis-label") }) {
                Text(if (collections.size == 1) "In 1 collection" else "In ${collections.size} collections")
            }
            Div(attrs = { classes("bd-vis-pills") }) {
                collections.forEach { collection ->
                    Pill(label = collection.name, icon = WebIcon.Layers, onClick = { onOpenCollection(collection.id) })
                }
            }
        }
    }
}

@Composable
private fun Stranded(
    isRestoring: Boolean,
    onRestoreToAllBooks: () -> Unit,
    onAddToCollection: () -> Unit,
) {
    // One amber block, words and both fixes inside it, as the canvas draws it.
    Div(attrs = { classes("bd-vis-stranded") }) {
        Div(attrs = { classes("bd-vis-callout") }) {
            Span(attrs = { classes("bd-vis-alert") }) { Icon(WebIcon.Alert, size = ALERT_ICON) }
            Div(attrs = { classes("bd-vis-says") }) {
                P(attrs = { classes("bd-vis-head") }) { Text("Hidden from all members") }
                P(attrs = { classes("bd-vis-why") }) { Text("It isn’t in any collection, so only admins can see it.") }
            }
        }
        Div(attrs = { classes("bd-vis-fixes") }) {
            // No confirmation (spec §7): it restores what was meant to be public, and can be undone.
            Button(kind = ButtonKind.Primary, onClick = { onRestoreToAllBooks() }, enabled = !isRestoring) {
                Text(if (isRestoring) "Showing to all members…" else "Show to all members")
            }
            Button(kind = ButtonKind.Secondary, onClick = { onAddToCollection() }) { Text("Add to a collection") }
        }
    }
}

private fun headline(
    hiddenFrom: HiddenFrom,
    expanded: Boolean,
): String =
    when (hiddenFrom) {
        HiddenFrom.Nobody -> "Every member can see it"
        HiddenFrom.Everyone -> "Hidden from all members"
        is HiddenFrom.Members -> "Hidden from ${nameList(hiddenFrom.names, expanded)}"
    }

/** "Alice", "Alice and Ben", "Alice, Ben and Cy", or "Alice, Dev, Hana and 2 others". */
internal fun nameList(
    names: List<String>,
    expanded: Boolean,
): String {
    val summary = HiddenFromNames.summarize(names, expanded)
    val shown = summary.shown
    return when {
        summary.othersCount == 1 -> "${shown.joinToString(", ")} and 1 other"
        summary.othersCount > 1 -> "${shown.joinToString(", ")} and ${summary.othersCount} others"
        shown.size == 1 -> shown.single()
        else -> "${shown.dropLast(1).joinToString(", ")} and ${shown.last()}"
    }
}

private fun reason(
    hiddenFrom: HiddenFrom,
    collections: List<CollectionRef>,
): String {
    val only = collections.singleOrNull()?.name
    return when (hiddenFrom) {
        is HiddenFrom.Members -> {
            if (only != null) "Only people in $only can see it." else "Anyone in at least one of these collections can see it."
        }

        HiddenFrom.Nobody -> {
            if (only != null) "Every member is in $only." else "Every member is in at least one of these collections."
        }

        HiddenFrom.Everyone -> {
            if (only != null) {
                "No member is in $only yet, so only admins can see it."
            } else {
                "No member is in any of these collections yet, so only admins can see it."
            }
        }
    }
}

private const val NOTE_ICON = 13

private const val ALERT_ICON = 18
