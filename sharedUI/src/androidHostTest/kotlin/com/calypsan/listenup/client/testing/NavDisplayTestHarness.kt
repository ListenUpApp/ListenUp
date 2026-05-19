package com.calypsan.listenup.client.testing

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import org.koin.compose.KoinApplication
import org.koin.core.module.Module

/**
 * Phase A test harness. Composes a NavDisplay with both standard entry decorators
 * installed and exposes helpers for boundary tests.
 *
 * Designed for reuse:
 *  - Phase B extends with DeepLinkTestHarness for Intent-driven tests
 *  - Phase C extends with per-platform graph-coverage assertions
 *
 * Process-death survival is exercised via Compose UI-Test's [StateRestorationTester],
 * which is the canonical mechanism for emulating an Activity / process recreation
 * inside a Robolectric host test — it drives the same `SaveableStateRegistry.performSave()`
 * → re-instantiate → restore round-trip that `rememberNavBackStack` relies on.
 */
class NavDisplayTestHarness(
    private val composeRule: ComposeContentTestRule,
) {
    private val stateRestorationTester = StateRestorationTester(composeRule)
    private lateinit var backStack: NavBackStack<NavKey>

    /**
     * Compose a NavDisplay starting at [initialKey] with both standard decorators.
     * The optional [entryProviderBlock] lets callers register `entry<Key> { ... }`
     * handlers inside the entryProvider DSL; when absent, every NavKey renders an
     * empty Box.
     *
     * The optional [koinModule] supplies a test-scoped Koin module wrapping the
     * NavDisplay in a `KoinApplication { }`. Pass a module with a `viewModel { }`
     * binding when boundary tests need to resolve a VM via `koinViewModel()`.
     * Reusable for Phase B/C tests.
     */
    fun composeBackStack(
        initialKey: NavKey,
        koinModule: Module? = null,
        entryProviderBlock: (EntryProviderScope<NavKey>.() -> Unit)? = null,
    ) {
        stateRestorationTester.setContent {
            val display: @Composable () -> Unit = {
                val stack = rememberNavBackStack(initialKey)
                SideEffect { backStack = stack }
                NavDisplay(
                    backStack = stack,
                    entryDecorators =
                        listOf(
                            rememberSaveableStateHolderNavEntryDecorator(),
                            rememberViewModelStoreNavEntryDecorator(),
                        ),
                    entryProvider =
                        entryProvider(
                            fallback = { key -> NavEntry(key) { Box(Modifier) } },
                        ) {
                            if (entryProviderBlock != null) {
                                entryProviderBlock()
                            }
                        },
                )
            }
            if (koinModule != null) {
                KoinApplication(application = { modules(koinModule) }) {
                    display()
                }
            } else {
                display()
            }
        }
    }

    /**
     * The current back-stack contents. Snapshot read; do not retain across recompositions.
     */
    val currentBackStack: List<NavKey> get() = backStack.toList()

    /**
     * Push a key onto the back stack.
     */
    fun navigate(key: NavKey) {
        composeRule.runOnIdle { backStack.add(key) }
    }

    /**
     * Pop the top entry.
     */
    fun pop() {
        composeRule.runOnIdle {
            if (backStack.isNotEmpty()) backStack.removeAt(backStack.lastIndex)
        }
    }

    /**
     * Drive a SavedStateRegistry save → re-instantiate → restore round-trip via
     * Compose UI-Test's [StateRestorationTester.emulateSavedInstanceStateRestore],
     * then return the restored back-stack contents. Used to simulate process death.
     *
     * After this call returns, [currentBackStack] reflects the restored state. The
     * `SideEffect { backStack = stack }` inside [composeBackStack] re-binds the
     * harness's reference to the freshly-restored [NavBackStack] instance during
     * the second composition pass.
     */
    fun simulateProcessDeath(): List<NavKey> {
        stateRestorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        return backStack.toList()
    }
}
