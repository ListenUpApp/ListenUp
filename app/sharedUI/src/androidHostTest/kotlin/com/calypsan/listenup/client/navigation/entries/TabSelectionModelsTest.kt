package com.calypsan.listenup.client.navigation.entries

import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.features.shell.ShellDestination
import com.calypsan.listenup.client.presentation.books.BookMultiSelectViewModel
import com.calypsan.listenup.client.presentation.books.SelectionMode
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Home, Library and Discover each own their selection; leaving a tab ends the selection made on it. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class TabSelectionModelsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun startKoinWithSelectionModel() {
        startKoin {
            modules(
                module {
                    factory {
                        BookMultiSelectViewModel(
                            userRepository =
                                mock<UserRepository>(MockMode.autoUnit) {
                                    every { observeCurrentUser() } returns flowOf(null)
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
                },
            )
        }
    }

    @After
    fun stopKoinAfter() {
        stopKoin()
    }

    private fun resolve(): TabSelectionModels {
        lateinit var models: TabSelectionModels
        composeRule.setContent { models = rememberTabSelectionModels() }
        composeRule.waitForIdle()
        return models
    }

    @Test
    fun `each tab gets its own selection model`() {
        val models = resolve()

        (models.home === models.library) shouldBe false
        (models.library === models.discover) shouldBe false
        (models.home === models.discover) shouldBe false
    }

    @Test
    fun `a selection made in Library is not selected on Home`() {
        val models = resolve()

        models.library.enterSelectionMode("b1")

        models.library.selectionMode.value shouldNotBe SelectionMode.None
        models.home.selectionMode.value shouldBe SelectionMode.None
    }

    @Test
    fun `leaving a tab ends only the selection made on it`() {
        val models = resolve()
        models.library.enterSelectionMode("b1")
        models.home.enterSelectionMode("b2")

        models.exitSelectionOf(ShellDestination.Library)

        models.library.selectionMode.value shouldBe SelectionMode.None
        models.home.selectionMode.value shouldNotBe SelectionMode.None
    }
}
