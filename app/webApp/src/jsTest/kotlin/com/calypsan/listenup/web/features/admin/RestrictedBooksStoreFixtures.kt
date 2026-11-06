package com.calypsan.listenup.web.features.admin

import kotlinx.coroutines.flow.MutableStateFlow

/** A set that never changes — the shape specs use in place of the graph. */
fun fixedRestrictedBooks(ids: Set<String> = emptySet()): OpenRestrictedBooks =
    { RestrictedBooksSession(MutableStateFlow(ids), close = {}) }
