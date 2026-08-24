package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.attributes.InputType

/**
 * One attached relation, reduced to what a chip needs.
 *
 * A book's contributors, series, genres, tags, moods and collections are six different types in
 * the ViewModel and one thing on screen: a labelled chip you can remove, over a search box that
 * offers more. Projecting them all to `{ id, label }` is what lets [RelationField] serve all six
 * instead of the form growing six near-identical editors — the same reduction iOS makes with its
 * `EditableRelation`.
 */
data class RelationChip(
    /** The key the caller uses to look the real object back up when this chip is removed. */
    val id: String,
    /** What the reader sees. Precomputed by the caller, so this component formats nothing. */
    val label: String,
)

/**
 * Search-and-attach for one relation: the chips already on the book, plus a box to find more.
 *
 * [onCreate] is what separates a relation you may invent from one you may only choose. Genres are
 * system-controlled and collections are curated, so both pass null and the field will only ever
 * offer what already exists. Tags and moods pass a handler, and typing a name that matches nothing
 * offers to create it.
 *
 * [trailing] renders inside each chip, before its remove button — the seam series uses for its
 * sequence number.
 *
 * ⛔ **Creating is a deliberate, separate affordance — never a side effect of pressing Enter.**
 * These records are global: a mistyped tag invented here shows up in everybody's library, not just
 * on this book. Making it a distinct "Create" button costs one click and makes accidental
 * vocabulary pollution something a reader has to actually choose.
 */
@Composable
fun RelationField(
    label: String,
    attached: List<RelationChip>,
    query: String,
    results: List<RelationChip>,
    onQueryChange: (String) -> Unit,
    onSelect: (RelationChip) -> Unit,
    onRemove: (RelationChip) -> Unit,
    loading: Boolean = false,
    onCreate: ((String) -> Unit)? = null,
    offline: Boolean = false,
    placeholder: String = "Search…",
    id: String? = null,
    trailing: (@Composable (RelationChip) -> Unit)? = null,
) {
    val fieldId = rememberFieldId(id)
    Div(attrs = { classes("f-wrap") }) {
        Label(attrs = {
            classes("f-label")
            attr("for", fieldId)
        }) { Text(label) }

        if (attached.isNotEmpty()) {
            Div(attrs = { classes("rel-chips") }) {
                attached.forEach { chip ->
                    Div(attrs = { classes("rel-chip") }) {
                        Text(chip.label)
                        // A slot rather than a second component: series is this same chip plus a
                        // sequence box, and forking the control over one input would leave two
                        // search fields to keep in step.
                        trailing?.invoke(chip)
                        Button(attrs = {
                            classes("rel-x")
                            attr("type", "button")
                            // Named for the thing it removes: a row of identical "Remove" buttons
                            // is unusable by anyone who cannot see which chip they sit on.
                            attr("aria-label", "Remove ${chip.label}")
                            onClick { onRemove(chip) }
                        }) { Text("×") }
                    }
                }
            }
        }

        Div(attrs = { classes("f-box") }) {
            Input(type = InputType.Text) {
                classes("f-input")
                value(query)
                attr("placeholder", placeholder)
                attr("id", fieldId)
                // ⛔ This box lives inside a real <form> (Book Edit). Without swallowing Enter,
                // the browser's implicit submission would SAVE THE BOOK when someone hits Enter
                // while searching for a contributor — turning a search box into a save button,
                // and contradicting this field's own rule that nothing happens on Enter.
                onKeyDown { event -> if (event.key == "Enter") event.preventDefault() }
                onInput { event -> onQueryChange(event.value) }
            }
        }

        if (query.isNotBlank()) {
            Div(attrs = { classes("rel-results") }) {
                when {
                    loading -> {
                        Div(attrs = { classes("rel-hint") }) { Text("Searching…") }
                    }

                    results.isNotEmpty() -> {
                        results.forEach { result ->
                            Button(attrs = {
                                classes("rel-result")
                                attr("type", "button")
                                onClick { onSelect(result) }
                            }) { Text(result.label) }
                        }
                    }

                    onCreate != null -> {
                        Button(attrs = {
                            classes("rel-result", "rel-create")
                            attr("type", "button")
                            onClick { onCreate(query) }
                        }) { Text("Create \"$query\"") }
                    }

                    // Nothing found and nothing may be invented — say so, rather than showing an
                    // empty box that looks like it is still loading.
                    else -> {
                        Div(attrs = { classes("rel-hint") }) { Text("No matches.") }
                    }
                }
                if (offline) {
                    // Never stranded: the search fell back to what this device already holds, and
                    // saying so is the difference between "no such series" and "could not ask".
                    Div(attrs = { classes("rel-hint") }) { Text("Offline — showing matches held on this device.") }
                }
            }
        }
    }
}
