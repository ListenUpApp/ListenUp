package com.calypsan.listenup.client.design.components

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.domain.model.BookVisibility
import com.calypsan.listenup.client.domain.repository.BookVisibilityRepository
import com.calypsan.listenup.client.presentation.visibility.RestrictedBooksViewModel
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinApplication
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner

/** The root provider hands the restricted set from its ViewModel to every card below it. */
@RunWith(RobolectricTestRunner::class)
class ProvideRestrictedBookIdsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun stopGlobalKoin() {
        stopKoin()
    }

    @Test
    fun `a descendant reads the set the repository emits`() {
        val repository =
            object : BookVisibilityRepository {
                override fun observeRestrictedBookIds(): Flow<Set<BookId>> = flowOf(setOf(BookId("b1"), BookId("b2")))

                override fun observeBookVisibility(bookId: BookId): Flow<BookVisibility?> = flowOf(null)
            }
        composeRule.setContent {
            KoinApplication(application = { modules(module { viewModel { RestrictedBooksViewModel(repository) } }) }) {
                ProvideRestrictedBookIds {
                    Text(LocalRestrictedBookIds.current.sorted().joinToString())
                }
            }
        }
        composeRule.onNodeWithText("b1, b2").assertExists()
    }
}
