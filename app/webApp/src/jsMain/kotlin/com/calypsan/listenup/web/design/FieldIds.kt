package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

private var nextFieldId = 0

/**
 * The id a field's `<label for>` and its control share.
 *
 * Association is generated rather than optional. Every field primitive already accepted an [id],
 * but nothing supplied one at most call sites and no label pointed at anything — so a screen reader
 * announced an unlabelled control, and clicking a label did not focus its field. Leaving that to
 * each call site to remember means it is wrong wherever someone forgets, which was everywhere.
 *
 * [explicit] still wins, because specs and deep links address fields by known id
 * (`#auth-email`), and a generated one would break them.
 *
 * The counter is process-wide and the value is remembered per composition instance, so a field
 * keeps its id across recompositions and no two live fields collide. JS is single-threaded, so the
 * increment needs no synchronisation.
 */
@Composable
internal fun rememberFieldId(explicit: String?): String {
    val generated = remember { "lu-field-${nextFieldId++}" }
    return explicit ?: generated
}
