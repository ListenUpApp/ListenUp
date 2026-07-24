package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.client.domain.model.AccessMode
import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.domain.model.Library
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.LibraryRepository
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for [AdminCollectionsViewModel] (Collections-2a — Room reads + RPC writes).
 *
 * The list loads reactively from [CollectionRepository.observeCollections]; create/delete
 * dispatch to the repository, and failures surface on [ErrorBus] plus a transient
 * `error` on [AdminCollectionsUiState.Ready].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminCollectionsViewModelTest :
    FunSpec({
        val dispatcher = StandardTestDispatcher()
        beforeSpec { Dispatchers.setMain(dispatcher) }
        afterSpec { Dispatchers.resetMain() }

        fun collection(
            id: String = "c1",
            name: String = "Collection $id",
            bookCount: Int = 0,
        ) = Collection(
            id = id,
            name = name,
            ownerId = "owner1",
            isInbox = false,
            isSystem = false,
            bookCount = bookCount,
            callerPermission = SharePermission.Write,
            isOwner = true,
        )

        fun fixture(collectionsFlow: MutableStateFlow<List<Collection>>): Triple<AdminCollectionsViewModel, CollectionRepository, LibraryRepository> {
            val repo: CollectionRepository = mock()
            val libraryRepo: LibraryRepository = mock()
            every { repo.observeCollections() } returns collectionsFlow
            every { libraryRepo.observeAll() } returns
                MutableStateFlow(
                    listOf(
                        Library(
                            id = "lib1",
                            name = "Main",
                            metadataPrecedence = "",
                            accessMode = AccessMode.OPEN,
                            createdByUserId = null,
                            createdAt = 0L,
                            revision = 0L,
                        ),
                    ),
                )
            return Triple(
                AdminCollectionsViewModel(repo, libraryRepo, ErrorBus()),
                repo,
                libraryRepo,
            )
        }

        test("Ready emitted with collections after first emission") {
            runTest(dispatcher) {
                val flow = MutableStateFlow(listOf(collection("a", "Alpha"), collection("b", "Beta")))
                val (vm, _, _) = fixture(flow)
                advanceUntilIdle()
                val ready = vm.state.value.shouldBeInstanceOf<AdminCollectionsUiState.Ready>()
                ready.collections.map { it.id } shouldBe listOf("a", "b")
            }
        }

        test("createCollection happy-path sets createSuccess and sources libraryId") {
            runTest(dispatcher) {
                val flow = MutableStateFlow(listOf(collection("a")))
                val (vm, repo, _) = fixture(flow)
                everySuspend { repo.create("lib1", "New") } returns AppResult.Success(collection("new", "New"))
                advanceUntilIdle()

                vm.createCollection("New")
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<AdminCollectionsUiState.Ready>()
                ready.createSuccess shouldBe true
                ready.isCreating shouldBe false
            }
        }

        test("createCollection failure surfaces a transient error") {
            runTest(dispatcher) {
                val flow = MutableStateFlow(listOf(collection("a")))
                val (vm, repo, _) = fixture(flow)
                everySuspend { repo.create(any(), any()) } returns
                    AppResult.Failure(ValidationError(message = "duplicate name"))
                advanceUntilIdle()

                vm.createCollection("Dup")
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<AdminCollectionsUiState.Ready>()
                ready.error shouldBe "duplicate name"
                ready.isCreating shouldBe false
            }
        }

        test("deleteCollection happy-path clears the deleting overlay") {
            runTest(dispatcher) {
                val flow = MutableStateFlow(listOf(collection("a")))
                val (vm, repo, _) = fixture(flow)
                everySuspend { repo.delete("a") } returns AppResult.Success(Unit)
                advanceUntilIdle()

                vm.deleteCollection("a")
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<AdminCollectionsUiState.Ready>()
                ready.deletingCollectionId shouldBe null
                ready.error shouldBe null
            }
        }

        test("deleteCollection failure surfaces a transient error") {
            runTest(dispatcher) {
                val flow = MutableStateFlow(listOf(collection("a")))
                val (vm, repo, _) = fixture(flow)
                everySuspend { repo.delete("a") } returns
                    AppResult.Failure(ValidationError(message = "not permitted"))
                advanceUntilIdle()

                vm.deleteCollection("a")
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<AdminCollectionsUiState.Ready>()
                ready.deletingCollectionId shouldBe null
                ready.error shouldBe "not permitted"
            }
        }
    })
