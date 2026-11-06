package com.calypsan.listenup.web.features.admin

import kotlinx.coroutines.flow.MutableStateFlow

/** A badge over numbers that never change — the shape specs use in place of the graph. */
fun fixedInboxBadge(
    heldCount: Int = 0,
    previewBookIds: List<String> = emptyList(),
): OpenInboxBadge = { InboxBadgeSession(MutableStateFlow(heldCount), MutableStateFlow(previewBookIds), close = {}) }
