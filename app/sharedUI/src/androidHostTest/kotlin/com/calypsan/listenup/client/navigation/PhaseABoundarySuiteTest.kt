package com.calypsan.listenup.client.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.calypsan.listenup.client.testing.FakeNavViewModel
import com.calypsan.listenup.client.testing.NavDisplayTestHarness
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicReference

/**
 * Navigation boundary suite. Tests that exercise the structural invariants the
 * navigation layer must satisfy:
 *
 *  - process-death back-stack survival via `rememberNavBackStack`.
 *  - every `NavDisplay` invocation site installs both standard entry decorators.
 *  - per-entry ViewModel scoping (VM-per-entry + cleared-on-pop).
 */
@RunWith(RobolectricTestRunner::class)
class PhaseABoundarySuiteTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `back stack survives process death via SavedStateRegistry round-trip`() {
        val harness = NavDisplayTestHarness(composeRule)

        // Build a non-trivial back stack against the real production routes.
        harness.composeBackStack(Shell)
        harness.navigate(BookDetail(bookId = "book-X"))
        harness.navigate(BookEdit(bookId = "book-X"))

        val expected = listOf(Shell, BookDetail("book-X"), BookEdit("book-X"))
        harness.currentBackStack shouldBe expected

        // Simulate process death and assert the stack restores.
        val restored = harness.simulateProcessDeath()
        withClue("Back stack did not survive process-death round-trip") {
            restored shouldBe expected
        }
    }

    @Test
    fun `every NavDisplay invocation site installs both standard entry decorators`() {
        // Static-analysis test: greps the navigation source files for `NavDisplay(`
        // and asserts that within 30 lines after each match, both
        // `rememberSaveableStateHolderNavEntryDecorator` and
        // `rememberViewModelStoreNavEntryDecorator` appear in the same NavDisplay
        // arglist.

        val authNav =
            readNavigationSource(
                "sharedUI/src/commonMain/kotlin/com/calypsan/listenup/client/navigation/AuthNavigation.kt",
            )
        val listenUpNav =
            readNavigationSource(
                "sharedUI/src/androidMain/kotlin/com/calypsan/listenup/client/navigation/ListenUpNavigation.kt",
            )

        val sites = mutableListOf<Pair<String, Int>>()
        sites += findNavDisplaySites("AuthNavigation.kt", authNav)
        sites += findNavDisplaySites("ListenUpNavigation.kt", listenUpNav)

        withClue("Expected 7 NavDisplay sites; found ${sites.size}") {
            sites.size shouldBe 7
        }

        for ((file, lineIdx) in sites) {
            val window = if (file.endsWith("AuthNavigation.kt")) authNav else listenUpNav
            val sliceEnd = (lineIdx + 30).coerceAtMost(window.size)
            val slice = window.subList(lineIdx, sliceEnd).joinToString("\n")
            val hasSaveable = "rememberSaveableStateHolderNavEntryDecorator" in slice
            val hasVmStore = "rememberViewModelStoreNavEntryDecorator" in slice
            withClue("$file:${lineIdx + 1} missing rememberSaveableStateHolderNavEntryDecorator") {
                hasSaveable shouldBe true
            }
            withClue("$file:${lineIdx + 1} missing rememberViewModelStoreNavEntryDecorator") {
                hasVmStore shouldBe true
            }
        }
    }

    private fun readNavigationSource(path: String): List<String> {
        // Test working directory is `client/sharedUI/`. Project root is two levels up.
        val candidates =
            listOf(
                java.io.File("../../$path"),
                java.io.File("../$path"),
                java.io.File(path),
            )
        val file =
            candidates.firstOrNull { it.exists() }
                ?: error("Could not locate $path; tried: ${candidates.joinToString { it.absolutePath }}")
        return file.readLines()
    }

    private fun findNavDisplaySites(
        name: String,
        lines: List<String>,
    ): List<Pair<String, Int>> =
        lines.mapIndexedNotNull { idx, line ->
            if (Regex("""\bNavDisplay\(""").containsMatchIn(line)) name to idx else null
        }

    @Test
    fun `koinViewModel resolves a distinct VM instance per NavEntry`() {
        val harness = NavDisplayTestHarness(composeRule)

        val capturedVMs = mutableListOf<FakeNavViewModel>()
        // Module override binds FakeNavViewModel via the canonical viewModel { } DSL,
        // ensuring koinViewModel() resolves against the per-entry ViewModelStore
        // that rememberViewModelStoreNavEntryDecorator provides.
        val testModule =
            module {
                viewModel { FakeNavViewModel() }
            }

        harness.composeBackStack(
            initialKey = BookDetail("book-X"),
            koinModule = testModule,
        ) {
            entry<BookDetail> { _ ->
                val vm: FakeNavViewModel = koinViewModel()
                SideEffect {
                    if (capturedVMs.none { it === vm }) {
                        capturedVMs.add(vm)
                    }
                }
            }
        }

        composeRule.waitForIdle()
        harness.navigate(BookDetail("book-Y"))
        composeRule.waitForIdle()

        withClue("Expected one VM per entry; got ${capturedVMs.size}") {
            capturedVMs.size shouldBe 2
        }
        withClue(
            "Two BookDetail entries with distinct args produced the SAME VM instance — " +
                "cross-detail VM contamination present.",
        ) {
            (capturedVMs[0] === capturedVMs[1]) shouldBe false
        }
    }

    @Test
    fun `popping a NavEntry triggers onCleared on the entry-scoped VM`() {
        val harness = NavDisplayTestHarness(composeRule)
        val capturedVM = AtomicReference<FakeNavViewModel?>()
        val testModule =
            module {
                viewModel { FakeNavViewModel() }
            }

        // Start with a base destination plus one BookDetail on top so pop() leaves
        // the harness on a valid entry rather than an empty stack.
        harness.composeBackStack(
            initialKey = Shell,
            koinModule = testModule,
        ) {
            entry<Shell> { _ ->
                Box(Modifier)
            }
            entry<BookDetail> { _ ->
                val vm: FakeNavViewModel = koinViewModel()
                SideEffect {
                    capturedVM.compareAndSet(null, vm)
                }
            }
        }

        composeRule.waitForIdle()
        harness.navigate(BookDetail("book-X"))
        composeRule.waitForIdle()

        val vm = capturedVM.get() ?: error("VM was not captured")
        withClue("VM cleared before pop") {
            vm.clearedFlag shouldBe false
        }

        harness.pop()
        composeRule.waitForIdle()

        withClue("Popped entry's VM did not have onCleared() called") {
            vm.clearedFlag shouldBe true
        }
    }
}
