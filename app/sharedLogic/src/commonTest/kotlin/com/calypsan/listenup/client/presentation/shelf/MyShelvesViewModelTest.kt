package com.calypsan.listenup.client.presentation.shelf

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.test.SimulatedFailure
import com.calypsan.listenup.core.ShelfId
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest

/**
 * Tests for [MyShelvesViewModel]: the My shelves list reads the reader's own shelves, waits for a user, says when the
 * read failed, and never shows an empty list as the reader's while the library is still arriving.
 */
class MyShelvesViewModelTest :
    FunSpec({
        fun user(id: String) =
            User(
                id = UserId(id),
                email = "$id@example.com",
                displayName = "Reader $id",
                isAdmin = false,
                createdAtMs = 1_704_067_200_000L,
                updatedAtMs = 1_704_067_200_000L,
            )

        fun shelf(
            id: String,
            name: String,
        ) = Shelf(
            id = ShelfId(id),
            name = name,
            description = null,
            isPrivate = false,
            ownerId = "u1",
            ownerDisplayName = "Reader u1",
            bookCount = 3,
            totalDurationSeconds = 3_600L,
            createdAtMs = 1_704_067_200_000L,
            updatedAtMs = 1_704_067_200_000L,
        )

        fun build(
            user: Flow<User?>,
            shelves: Flow<List<Shelf>>,
            arriving: Flow<Boolean> = MutableStateFlow(false),
        ): MyShelvesViewModel {
            val users: UserRepository = mock { every { observeCurrentUser() } returns user }
            val repo: ShelfRepository = mock { every { observeMyShelves(any()) } returns shelves }
            return MyShelvesViewModel(users, repo, arriving)
        }

        test("loading until a user is known, then the reader's own shelves") {
            runTest {
                val user = MutableStateFlow<User?>(null)
                val shelves = MutableStateFlow(listOf(shelf("s1", "Winter")))
                build(user, shelves).state.test {
                    awaitItem() shouldBe MyShelvesUiState.Loading
                    user.value = user("u1")
                    awaitItem().shouldBeInstanceOf<MyShelvesUiState.Ready>().shelves.map { it.name } shouldBe
                        listOf("Winter")
                }
            }
        }

        test("no shelves is Ready with an empty list, not Loading") {
            runTest {
                build(MutableStateFlow(user("u1")), MutableStateFlow(emptyList())).state.test {
                    var item = awaitItem()
                    while (item is MyShelvesUiState.Loading) item = awaitItem()
                    item.shouldBeInstanceOf<MyShelvesUiState.Ready>().shelves shouldBe emptyList()
                }
            }
        }

        test("while the library arrives an empty list stays Loading; a shelf that lands is shown at once") {
            runTest {
                val arriving = MutableStateFlow(true)
                val shelves = MutableStateFlow<List<Shelf>>(emptyList())
                build(MutableStateFlow(user("u1")), shelves, arriving).state.test {
                    awaitItem() shouldBe MyShelvesUiState.Loading
                    shelves.value = listOf(shelf("s1", "Winter"))
                    awaitItem().shouldBeInstanceOf<MyShelvesUiState.Ready>().shelves.map { it.name } shouldBe
                        listOf("Winter")
                    shelves.value = emptyList()
                    awaitItem() shouldBe MyShelvesUiState.Loading
                    arriving.value = false
                    awaitItem().shouldBeInstanceOf<MyShelvesUiState.Ready>().shelves shouldBe emptyList()
                }
            }
        }

        test("a failing shelf read is a typed error") {
            runTest {
                val failing = flow<List<Shelf>> { throw SimulatedFailure("db") }
                build(MutableStateFlow(user("u1")), failing).state.test {
                    var item = awaitItem()
                    while (item is MyShelvesUiState.Loading) item = awaitItem()
                    item.shouldBeInstanceOf<MyShelvesUiState.Error>()
                }
            }
        }
    })
