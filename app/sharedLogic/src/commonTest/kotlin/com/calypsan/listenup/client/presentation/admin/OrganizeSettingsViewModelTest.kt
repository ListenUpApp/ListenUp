package com.calypsan.listenup.client.presentation.admin

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.organize.OrganizePreviewDto
import com.calypsan.listenup.api.dto.organize.OrganizeSettingsDto
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.OrganizeRepository
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Organization is always on, so the screen's two actions are peers: saving the rules moves nothing,
 * and the sweep is a separate, previewed act. These pin the part of that split which is easy to get
 * wrong — what happens when the sweep finds there is nothing to do.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrganizeSettingsViewModelTest :
    FunSpec({

        fun emptyPreview() =
            OrganizePreviewDto(
                bookCount = 0,
                fileCount = 0,
                collisionCount = 0,
                entries = emptyList(),
                truncated = false,
            )

        fun previewWithWork() =
            OrganizePreviewDto(
                bookCount = 2,
                fileCount = 2,
                collisionCount = 0,
                entries = emptyList(),
                truncated = false,
            )

        fun viewModelOver(preview: OrganizePreviewDto): Pair<OrganizeSettingsViewModel, OrganizeRepository> {
            val repo =
                mock<OrganizeRepository> {
                    everySuspend { getSettings() } returns AppResult.Success(OrganizeSettingsDto())
                    everySuspend { preview(any()) } returns AppResult.Success(preview)
                    everySuspend { resumeRun() } returns AppResult.Success(null)
                }
            return OrganizeSettingsViewModel(repo, ErrorBus()) to repo
        }

        beforeTest { Dispatchers.setMain(StandardTestDispatcher()) }
        afterTest { Dispatchers.resetMain() }

        test("a sweep with nothing to do reports it and opens no dialog") {
            runTest {
                val (viewModel, _) = viewModelOver(emptyPreview())
                advanceUntilIdle()

                viewModel.events.test {
                    viewModel.organize()
                    advanceUntilIdle()

                    withClue("an empty plan must announce itself, not raise a consent dialog") {
                        awaitItem() shouldBe OrganizeSettingsEvent.AlreadyOrganized
                    }
                }
                // Confirming "do nothing" is the wrong interaction, and the dialog's body renders
                // only non-zero counts — so an empty plan would have shown an empty box.
                (viewModel.state.value as OrganizeSettingsUiState.Ready).preview.shouldBeNull()
            }
        }

        test("a sweep with real work still opens the consent dialog") {
            runTest {
                val (viewModel, _) = viewModelOver(previewWithWork())
                advanceUntilIdle()

                viewModel.organize()
                advanceUntilIdle()

                // The control for the test above: a guard that always short-circuited would pass
                // that one and silently disable the whole sweep.
                withClue("a plan with work must still be confirmed before anything moves") {
                    (viewModel.state.value as OrganizeSettingsUiState.Ready).preview.shouldNotBeNull()
                }
            }
        }
    })
