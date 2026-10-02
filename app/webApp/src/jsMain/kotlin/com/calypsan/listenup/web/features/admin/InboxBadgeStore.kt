package com.calypsan.listenup.web.features.admin

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.admin.InboxBadgeViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin

/**
 * The held-for-review count and the newest held covers, and the teardown for them.
 *
 * Open for as long as the shell is, like the unread badge: the count rides the Library nav item,
 * which outlives every page, and the Library page's inbox strip reads the same session.
 */
class InboxBadgeSession(
    val heldCount: StateFlow<Int>,
    val previewBookIds: StateFlow<List<String>>,
    val close: () -> Unit,
)

/** How the shell gets the held count. Production resolves the real ViewModel; specs hand over numbers. */
typealias OpenInboxBadge = () -> InboxBadgeSession

/** What the shell hands the nav item and the Library page: the count and the cover fan's ids. */
data class InboxBadgeState(
    val heldCount: Int = 0,
    val previewBookIds: List<String> = emptyList(),
)

/** The production badge: the shared [InboxBadgeViewModel] over the Room held set. */
fun graphInboxBadge(koin: Koin): OpenInboxBadge =
    {
        val viewModel = koin.get<InboxBadgeViewModel>()
        val store = ViewModelStore().apply { put("inbox-badge", viewModel) }
        InboxBadgeSession(
            heldCount = viewModel.heldCount,
            previewBookIds = viewModel.previewBookIds,
            close = store::clear,
        )
    }

/** A badge over numbers that never change — the shape specs use in place of the graph. */
fun fixedInboxBadge(
    heldCount: Int = 0,
    previewBookIds: List<String> = emptyList(),
): OpenInboxBadge = { InboxBadgeSession(MutableStateFlow(heldCount), MutableStateFlow(previewBookIds), close = {}) }
