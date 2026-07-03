package com.calypsan.listenup.client.presentation.shelf

import app.cash.turbine.test
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.usecase.shelf.CreateShelfUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.DeleteShelfUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.UpdateShelfUseCase
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.core.ShelfId
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for [CreateEditShelfViewModel] — the create/edit privacy-aware save flow.
 *
 * Covers: create with the privacy toggle, edit-mode seeding of `isPrivate`,
 * and the failure branch routing through the error bus + Error state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreateEditShelfViewModelTest :
    FunSpec({

        val testDispatcher = StandardTestDispatcher()

        fun shelf(isPrivate: Boolean) =
            Shelf(
                id = ShelfId("s1"),
                name = "Reads",
                description = "desc",
                isPrivate = isPrivate,
                ownerId = "owner",
                ownerDisplayName = "Owner",
                bookCount = 0,
                totalDurationSeconds = 0,
                createdAtMs = 0,
                updatedAtMs = 0,
            )

        fun viewModel(
            create: CreateShelfUseCase = mock(),
            update: UpdateShelfUseCase = mock(),
            delete: DeleteShelfUseCase = mock(),
            repo: ShelfRepository = mock(),
            errorBus: ErrorBus = ErrorBus(),
        ) = CreateEditShelfViewModel(create, update, delete, repo, errorBus)

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        test("save in create mode forwards the privacy flag") {
            runTest {
                val create: CreateShelfUseCase =
                    mock {
                        everySuspend { invoke(any(), any(), any()) } returns AppResult.Success(shelf(isPrivate = true))
                    }
                val vm = viewModel(create = create)
                vm.initCreate()

                vm.save(name = "Reads", description = "desc", isPrivate = true)
                advanceUntilIdle()

                verifySuspend { create.invoke("Reads", "desc", true) }
            }
        }

        test("save in edit mode forwards the privacy flag to update") {
            runTest {
                val repo: ShelfRepository =
                    mock { everySuspend { getById(ShelfId("s1")) } returns shelf(isPrivate = false) }
                val update: UpdateShelfUseCase =
                    mock {
                        everySuspend {
                            invoke(any(), any(), any(), any())
                        } returns AppResult.Success(shelf(isPrivate = true))
                    }
                val vm = viewModel(update = update, repo = repo)
                vm.initEdit("s1")
                advanceUntilIdle()

                vm.save(name = "Reads", description = "desc", isPrivate = true)
                advanceUntilIdle()

                verifySuspend { update.invoke(ShelfId("s1"), "Reads", "desc", true) }
            }
        }

        test("initEdit seeds the private flag from the loaded shelf") {
            runTest {
                val repo: ShelfRepository =
                    mock { everySuspend { getById(ShelfId("s1")) } returns shelf(isPrivate = true) }
                val vm = viewModel(repo = repo)

                vm.initEdit("s1")
                advanceUntilIdle()

                val loaded = vm.state.value.shouldBeInstanceOf<CreateEditShelfUiState.Loaded>()
                loaded.isPrivate shouldBe true
            }
        }

        test("save failure emits to the error bus and sets Error state") {
            runTest {
                val bus = ErrorBus()
                val create: CreateShelfUseCase =
                    mock {
                        everySuspend {
                            invoke(any(), any(), any())
                        } returns AppResult.Failure(ValidationError(message = "bad"))
                    }
                val vm = viewModel(create = create, errorBus = bus)
                vm.initCreate()

                bus.errors.test {
                    vm.save(name = "X", description = "", isPrivate = false)
                    advanceUntilIdle()
                    awaitItem().shouldBeInstanceOf<ValidationError>()
                }
                vm.state.value.shouldBeInstanceOf<CreateEditShelfUiState.Error>()
            }
        }
    })
