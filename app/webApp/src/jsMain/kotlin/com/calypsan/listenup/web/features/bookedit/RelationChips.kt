package com.calypsan.listenup.web.features.bookedit

import com.calypsan.listenup.client.domain.model.EditableCollection
import com.calypsan.listenup.client.domain.model.EditableGenre
import com.calypsan.listenup.client.domain.model.EditableMood
import com.calypsan.listenup.client.domain.model.EditableTag
import com.calypsan.listenup.web.design.RelationChip

/**
 * Projections from the ViewModel's `Editable*` types to the one shape a chip needs.
 *
 * Pure and separately testable, which is the point: the label rules are the only place these six
 * relations differ, so they are worth pinning without standing a form up around them.
 *
 * ⛔ **These rules exist twice already** — iOS has them in `BookEditFormatting`, Compose in its
 * `ClassificationSection`, and this is the third copy. They belong in `:app:sharedLogic` so all
 * three platforms title-case a tag the same way; pulling them up touches iOS and Android, so it is
 * tracked as its own change rather than smuggled into the web form.
 */
internal fun EditableGenre.toChip(): RelationChip = RelationChip(id = id, label = name)

internal fun EditableCollection.toChip(): RelationChip = RelationChip(id = id, label = name)

internal fun EditableTag.toChip(): RelationChip = RelationChip(id = id, label = slugLabel(slug))

internal fun EditableMood.toChip(): RelationChip = RelationChip(id = id, label = slugLabel(slug))

/**
 * Turns a storage slug into something a reader recognises: `found-family` → `Found Family`.
 *
 * Tags and moods are stored slugged because they are matched and deduplicated by machines; showing
 * the reader the machine's spelling is how "found-family" ends up printed on a book page.
 */
internal fun slugLabel(slug: String): String =
    slug
        .split('-', '_')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
