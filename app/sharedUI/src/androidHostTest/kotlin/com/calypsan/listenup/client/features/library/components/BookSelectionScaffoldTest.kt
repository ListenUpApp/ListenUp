package com.calypsan.listenup.client.features.library.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.design.components.LocalSnackbarHostState
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.presentation.books.BookMultiSelectViewModel
import com.calypsan.listenup.client.presentation.books.SelectionMode
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val TIMEOUT_MS = 5_000L

/**
 * Who is offered bulk editing, and what the offer carries.
 *
 * Two conditions have to hold together before the action is drawn, and each fails differently.
 * Library metadata is admin-owned — the same ownership that already gates collections — so a
 * non-admin must not be shown a door they cannot walk through. And a host that has not wired the
 * callback must not be shown one either: a screen that has no route to the editor would render a
 * button that does nothing, which reads as a broken app rather than an absent feature.
 *
 * The last test is the one that would go unnoticed: the action can be present, enabled, and route
 * to the editor while carrying the wrong books. It asserts the payload is the live selection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h2400dp")
class BookSelectionScaffoldTest {
    @get:Rule
    val composeRule = createComposeRule()

    /**
     * A real [BookMultiSelectViewModel] over stubbed repositories. Only the three observed streams
     * are reached during a render; the bulk-action use cases are never invoked here.
     */
    private fun multiSelect(isAdmin: Boolean): BookMultiSelectViewModel {
        val user =
            User(
                id = UserId("user-1"),
                email = "reader@example.com",
                displayName = "Reader",
                isAdmin = isAdmin,
                createdAtMs = 0L,
                updatedAtMs = 0L,
            )
        return BookMultiSelectViewModel(
            userRepository =
                mock<UserRepository>(MockMode.autoUnit) {
                    every { observeCurrentUser() } returns flowOf(user)
                },
            collectionRepository =
                mock<CollectionRepository>(MockMode.autoUnit) {
                    every { observeCollections() } returns flowOf(emptyList())
                },
            shelfRepository =
                mock<ShelfRepository>(MockMode.autoUnit) {
                    every { observeMyShelves(any()) } returns flowOf(emptyList())
                },
            addBooksToShelfUseCase = mock(MockMode.autoUnit),
            addBooksToCollectionUseCase = mock(MockMode.autoUnit),
            createShelfUseCase = mock(MockMode.autoUnit),
            createCollectionUseCase = mock(MockMode.autoUnit),
            errorBus = ErrorBus(),
        )
    }

    private fun render(
        isAdmin: Boolean,
        selection: List<String> = listOf("book-1", "book-2"),
        onEditSelected: ((List<String>, () -> Unit) -> Unit)? = { _, _ -> },
    ): BookMultiSelectViewModel {
        val viewModel = multiSelect(isAdmin)
        viewModel.enterSelectionMode(selection.first())
        selection.drop(1).forEach(viewModel::toggleSelection)

        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSnackbarHostState provides SnackbarHostState()) {
                    BookSelectionScaffold(
                        multiSelect = viewModel,
                        onEditSelected = onEditSelected,
                    )
                }
            }
        }
        return viewModel
    }

    /** Waits for a label to appear, so a negative assertion never passes on an unsettled stream. */
    private fun awaitLabel(label: String) {
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `a non-admin is not offered bulk editing`() {
        render(isAdmin = false)

        // "Shelf" is the every-user action: its presence proves the toolbar is up, so what follows
        // is the admin gate and not an unrendered overlay.
        awaitLabel("Shelf")
        composeRule.onNodeWithText("Collection").assertDoesNotExist()
        composeRule.onNodeWithText("Edit").assertDoesNotExist()
    }

    @Test
    fun `an admin is offered bulk editing`() {
        render(isAdmin = true)

        awaitLabel("Edit")
        composeRule.onNodeWithText("Edit").assertIsDisplayed()
    }

    @Test
    fun `a host with no route to the editor offers no edit action, admin or not`() {
        render(isAdmin = true, onEditSelected = null)

        // "Collection" is admin-only, so its arrival proves the admin stream has settled — the
        // absence below is therefore the missing route, not a race.
        awaitLabel("Collection")
        composeRule.onNodeWithText("Edit").assertDoesNotExist()
    }

    @Test
    fun `editing reports the books that are actually selected`() {
        var edited: List<String>? = null
        render(
            isAdmin = true,
            selection = listOf("book-1", "book-2", "book-3"),
            onEditSelected = { ids, _ -> edited = ids },
        )

        awaitLabel("Edit")
        composeRule.onNodeWithText("Edit").performClick()

        edited?.size shouldBe 3
        edited?.toSet() shouldBe setOf("book-1", "book-2", "book-3")
    }

    @Test
    fun `the editor is handed a way to end the selection it was opened over`() {
        var endSelection: (() -> Unit)? = null
        val viewModel =
            render(
                isAdmin = true,
                onEditSelected = { _, end -> endSelection = end },
            )

        awaitLabel("Edit")
        composeRule.onNodeWithText("Edit").performClick()
        viewModel.selectionMode.value shouldBe SelectionMode.Active(setOf("book-1", "book-2"))

        endSelection!!.invoke()

        viewModel.selectionMode.value shouldBe SelectionMode.None
    }
}
