package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.domain.model.CollectionShare
import com.calypsan.listenup.client.domain.repository.AdminRepository
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.core.AppResult
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for [AdminCollectionDetailViewModel] (Collections-2a).
 *
 * The collection, its book ids, and its shares are observed reactively from
 * [CollectionRepository]; rename / remove-book / share / revoke-share dispatch to the
 * repository, with failures surfacing on [ErrorBus] plus a transient `error`.
 */
class AdminCollectionDetailViewModelTest :
    FunSpec({
        val dispatcher = StandardTestDispatcher()
        beforeSpec { Dispatchers.setMain(dispatcher) }
        afterSpec { Dispatchers.resetMain() }

        fun collection(
            id: String = "c1",
            name: String = "Favorites",
        ) = Collection(
            id = id,
            name = name,
            ownerId = "owner1",
            isInbox = false,
            isGlobalAccess = false,
            bookCount = 0,
            callerPermission = SharePermission.Write,
            isOwner = true,
        )

        class Fixture {
            val repo: CollectionRepository = mock()
            val adminRepo: AdminRepository = mock()
            val userRepo: UserRepository = mock()
            val collectionsFlow = MutableStateFlow(listOf(collection()))
            val booksFlow = MutableStateFlow<List<String>>(emptyList())
            val sharesFlow = MutableStateFlow<List<CollectionShare>>(emptyList())

            fun build(): AdminCollectionDetailViewModel {
                every { repo.observeCollections() } returns collectionsFlow
                every { repo.observeCollectionBooks("c1") } returns booksFlow
                every { repo.observeShares("c1") } returns sharesFlow
                return AdminCollectionDetailViewModel("c1", repo, adminRepo, userRepo, ErrorBus())
            }
        }

        test("Ready emitted with collection, books, and shares") {
            runTest(dispatcher) {
                val f = Fixture()
                f.booksFlow.value = listOf("b1", "b2")
                f.sharesFlow.value = listOf(CollectionShare("s1", "c1", "u1", SharePermission.Read))
                val vm = f.build()
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<AdminCollectionDetailUiState.Ready>()
                ready.collection.id shouldBe "c1"
                ready.books.map { it.id } shouldBe listOf("b1", "b2")
                ready.shares.map { it.userId } shouldBe listOf("u1")
            }
        }

        test("missing collection yields Error") {
            runTest(dispatcher) {
                val f = Fixture()
                f.collectionsFlow.value = emptyList()
                val vm = f.build()
                advanceUntilIdle()
                vm.state.value.shouldBeInstanceOf<AdminCollectionDetailUiState.Error>()
            }
        }

        test("saveName dispatches rename and sets saveSuccess") {
            runTest(dispatcher) {
                val f = Fixture()
                everySuspend { f.repo.rename("c1", "Renamed") } returns AppResult.Success(collection(name = "Renamed"))
                val vm = f.build()
                advanceUntilIdle()
                vm.updateName("Renamed")
                vm.saveName()
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<AdminCollectionDetailUiState.Ready>()
                ready.saveSuccess shouldBe true
                ready.isSaving shouldBe false
            }
        }

        test("saveName failure surfaces a transient error") {
            runTest(dispatcher) {
                val f = Fixture()
                everySuspend { f.repo.rename(any(), any()) } returns
                    AppResult.Failure(ValidationError(message = "name taken"))
                val vm = f.build()
                advanceUntilIdle()
                vm.updateName("Renamed")
                vm.saveName()
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<AdminCollectionDetailUiState.Ready>()
                ready.error shouldBe "name taken"
                ready.isSaving shouldBe false
            }
        }

        test("removeBook dispatches and clears the overlay") {
            runTest(dispatcher) {
                val f = Fixture()
                f.booksFlow.value = listOf("b1")
                everySuspend { f.repo.removeBook("c1", "b1") } returns AppResult.Success(Unit)
                val vm = f.build()
                advanceUntilIdle()
                vm.removeBook("b1")
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<AdminCollectionDetailUiState.Ready>()
                ready.removingBookId shouldBe null
            }
        }

        test("revokeShare dispatches and clears the overlay") {
            runTest(dispatcher) {
                val f = Fixture()
                f.sharesFlow.value = listOf(CollectionShare("s1", "c1", "u1", SharePermission.Read))
                everySuspend { f.repo.revokeShare("c1", "u1") } returns AppResult.Success(Unit)
                val vm = f.build()
                advanceUntilIdle()
                vm.revokeShare("u1")
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<AdminCollectionDetailUiState.Ready>()
                ready.removingShareUserId shouldBe null
            }
        }

        test("shareWithUser dispatches share at read permission and closes the sheet") {
            runTest(dispatcher) {
                val f = Fixture()
                everySuspend { f.repo.share("c1", "u2", SharePermission.Read) } returns
                    AppResult.Success(CollectionShare("s2", "c1", "u2", SharePermission.Read))
                val vm = f.build()
                advanceUntilIdle()
                vm.shareWithUser("u2")
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<AdminCollectionDetailUiState.Ready>()
                ready.isSharing shouldBe false
                ready.showAddMemberSheet shouldBe false
            }
        }
    })
