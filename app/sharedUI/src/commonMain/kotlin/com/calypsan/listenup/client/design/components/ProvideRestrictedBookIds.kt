package com.calypsan.listenup.client.design.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.presentation.visibility.RestrictedBooksViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * Publish the restricted-book set from [RestrictedBooksViewModel] as [LocalRestrictedBookIds] to
 * every card in [content] — the one place the lock's data enters the UI, so the authenticated root
 * wraps itself in it once and each card only reads the local. The set is admin-gated upstream, so a
 * member's device publishes the empty set.
 */
@Composable
internal fun ProvideRestrictedBookIds(content: @Composable () -> Unit) {
    val viewModel: RestrictedBooksViewModel = koinViewModel()
    val restrictedBookIds by viewModel.restrictedBookIds.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalRestrictedBookIds provides restrictedBookIds, content = content)
}
