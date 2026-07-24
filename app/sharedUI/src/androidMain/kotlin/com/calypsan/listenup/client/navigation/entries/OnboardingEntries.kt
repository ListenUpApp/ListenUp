package com.calypsan.listenup.client.navigation.entries

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.navigation.LibrarySetup
import com.calypsan.listenup.client.navigation.Shell
import com.calypsan.listenup.client.presentation.startup.AppStartupViewModel
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/** Library setup (onboarding) navigation entry. */
internal fun EntryProviderScope<NavKey>.librarySetupEntry(
    backStack: NavBackStack<NavKey>,
    startupViewModel: AppStartupViewModel,
    scope: CoroutineScope,
    syncRepository: SyncRepository,
) {
    entry<LibrarySetup> {
        com.calypsan.listenup.client.features.setup.LibrarySetupScreen(
            onSetupComplete = {
                // Clear the stale needs-setup flag so the startup readiness overlay
                // can't re-latch on top of the shell after we navigate away.
                startupViewModel.onLibrarySetupComplete()
                // Trigger sync to pull newly scanned books
                scope.launch {
                    logger.info { "Library setup complete, triggering sync" }
                    syncRepository.sync()
                }
                // Navigate to main app, clearing library setup from back stack
                backStack.clear()
                backStack.add(Shell)
            },
        )
    }
}
