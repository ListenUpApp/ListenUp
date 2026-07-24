package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.client.domain.model.Genre

/**
 * Valid targets for moving [source] under a new parent: every other genre that is
 * not [source] itself and not one of [source]'s descendants (which would create a
 * cycle). Descendants are detected via the materialized-path prefix `source.path + "/"`
 * — the trailing slash prevents a sibling like `/fic-classics` from matching `/fic`.
 *
 * A `null` parent (top level) is offered by the UI separately and is not in this list.
 */
fun genreMoveCandidates(
    all: List<Genre>,
    source: Genre,
): List<Genre> = all.filter { it.id != source.id && !it.path.startsWith(source.path + "/") }
