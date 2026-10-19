package com.calypsan.listenup.client.presentation.shelf

import app.cash.turbine.ReceiveTurbine
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for [MyShelvesViewModel]: the My shelves list reads the reader's own shelves, waits for a user, says when the
 * read failed, and never shows an empty list as the reader's while the library is still arriving.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MyShelvesViewModelTest :
    FunSpec({
        // viewModelScope runs on Main; give it one the test controls, as the other ViewModel specs do (the Apple lane
        // has no Main of its own that a test can drive).
        beforeTest { Dispatchers.setMain(UnconfinedTestDispatcher()) }
        afterTest { Dispatchers.resetMain() }

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
                    awaitUntil { it !is MyShelvesUiState.Loading }.shouldBeInstanceOf<MyShelvesUiState.Ready>().shelves shouldBe
                        emptyList()
                }
            }
        }

        test("while the library arrives an empty list stays Loading, and a shelf that lands is shown at once") {
            runTest {
                val shelves = MutableStateFlow<List<Shelf>>(emptyList())
                build(MutableStateFlow(user("u1")), shelves, arriving = MutableStateFlow(true)).state.test {
                    awaitItem() shouldBe MyShelvesUiState.Loading
                    expectNoEvents()
                    shelves.value = listOf(shelf("s1", "Winter"))
                    awaitUntil { it is MyShelvesUiState.Ready }.shouldBeInstanceOf<MyShelvesUiState.Ready>().shelves.map {
                        it.name
                    } shouldBe listOf("Winter")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("once the library has arrived, no shelves is the reader's own empty list") {
            runTest {
                val arriving = MutableStateFlow(true)
                build(MutableStateFlow(user("u1")), MutableStateFlow(emptyList()), arriving).state.test {
                    awaitItem() shouldBe MyShelvesUiState.Loading
                    expectNoEvents()
                    arriving.value = false
                    awaitUntil { it is MyShelvesUiState.Ready }.shouldBeInstanceOf<MyShelvesUiState.Ready>().shelves shouldBe
                        emptyList()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a failing shelf read is a typed error") {
            runTest {
                val failing = flow<List<Shelf>> { throw SimulatedFailure("db") }
                build(MutableStateFlow(user("u1")), failing).state.test {
                    awaitUntil { it !is MyShelvesUiState.Loading }.shouldBeInstanceOf<MyShelvesUiState.Error>()
                }
            }
        }
    })

/** The first item [predicate] accepts, skipping any before it. */
private suspend fun <T> ReceiveTurbine<T>.awaitUntil(predicate: (T) -> Boolean): T {
    var item = awaitItem()
    while (!predicate(item)) item = awaitItem()
    return item
}
