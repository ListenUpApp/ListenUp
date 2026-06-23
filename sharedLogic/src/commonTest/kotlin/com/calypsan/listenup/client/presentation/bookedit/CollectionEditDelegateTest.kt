package com.calypsan.listenup.client.presentation.bookedit

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.presentation.bookedit.delegates.CollectionEditDelegate
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Tests for [CollectionEditDelegate].
 *
 * Mirrors the GenreTagEditDelegate test shape (state-mutation + onChangesMade signalling),
 * plus the reactive load of the book's current collections and the available list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CollectionEditDelegateTest :
    FunSpec({

        fun collection(
            id: String,
            name: String,
        ): Collection =
            Collection(
                id = id,
                name = name,
                ownerId = "admin-1",
                isInbox = false,
                isSystem = false,
                bookCount = 0,
                callerPermission = SharePermission.Write,
                isOwner = true,
            )

        class Fixture {
            val state = MutableStateFlow(BookEditUiState(bookId = "book-1"))
            val collectionRepository: CollectionRepository = mock()
            var changesMade = 0

            fun build(scope: CoroutineScope): CollectionEditDelegate =
                CollectionEditDelegate(
                    state = state,
                    collectionRepository = collectionRepository,
                    scope = scope,
                    onChangesMade = { changesMade++ },
                )
        }

        test("loadCollections populates current + available collections") {
            runTest {
                val fixture = Fixture()
                every { fixture.collectionRepository.observeCollections() } returns
                    flowOf(listOf(collection("c1", "Favorites"), collection("c2", "To Read")))
                every { fixture.collectionRepository.observeBookCollectionIds(any()) } returns
                    flowOf(listOf("c1"))
                val delegate = fixture.build(this)

                delegate.loadCollections("book-1")
                advanceUntilIdle()

                fixture.state.value.allCollections
                    .map { it.name } shouldContainExactly listOf("Favorites", "To Read")
                fixture.state.value.collections
                    .map { it.id } shouldContainExactly listOf("c1")
            }
        }

        test("selectCollection adds to state and signals a change") {
            runTest {
                val fixture = Fixture()
                every { fixture.collectionRepository.observeCollections() } returns flowOf(emptyList())
                every { fixture.collectionRepository.observeBookCollectionIds(any()) } returns flowOf(emptyList())
                val delegate = fixture.build(this)

                delegate.selectCollection(EditableCollection(id = "c2", name = "To Read"))

                fixture.state.value.collections
                    .map { it.id } shouldContainExactly listOf("c2")
                fixture.changesMade shouldBe 1
            }
        }

        test("selectCollection ignores duplicates") {
            runTest {
                val fixture = Fixture()
                every { fixture.collectionRepository.observeCollections() } returns flowOf(emptyList())
                every { fixture.collectionRepository.observeBookCollectionIds(any()) } returns flowOf(emptyList())
                val delegate = fixture.build(this)

                delegate.selectCollection(EditableCollection(id = "c1", name = "Favorites"))
                delegate.selectCollection(EditableCollection(id = "c1", name = "Favorites"))

                fixture.state.value.collections.size shouldBe 1
            }
        }

        test("removeCollection drops from state and signals a change") {
            runTest {
                val fixture = Fixture()
                every { fixture.collectionRepository.observeCollections() } returns flowOf(emptyList())
                every { fixture.collectionRepository.observeBookCollectionIds(any()) } returns flowOf(emptyList())
                val delegate = fixture.build(this)
                fixture.state.value =
                    fixture.state.value.copy(
                        collections = listOf(EditableCollection(id = "c1", name = "Favorites")),
                    )

                delegate.removeCollection(EditableCollection(id = "c1", name = "Favorites"))

                fixture.state.value.collections
                    .shouldBeEmpty()
                fixture.changesMade shouldBe 1
            }
        }

        test("collection search filters available collections by name, excluding already-selected") {
            runTest {
                val fixture = Fixture()
                every { fixture.collectionRepository.observeCollections() } returns
                    flowOf(listOf(collection("c1", "Favorites"), collection("c2", "Fantasy Picks")))
                every { fixture.collectionRepository.observeBookCollectionIds(any()) } returns flowOf(emptyList())
                val delegate = fixture.build(this)
                delegate.loadCollections("book-1")
                advanceUntilIdle()
                delegate.selectCollection(EditableCollection(id = "c1", name = "Favorites"))

                delegate.updateCollectionSearchQuery("fa")

                fixture.state.value.collectionSearchResults
                    .map { it.id } shouldContainExactly listOf("c2")
            }
        }

        test("loadCollections excludes system collections from allCollections") {
            runTest {
                val fixture = Fixture()
                val systemCollection =
                    Collection(
                        id = "all-books",
                        name = "All Books",
                        ownerId = "system",
                        isInbox = false,
                        isSystem = true,
                        bookCount = 100,
                        callerPermission = SharePermission.Write,
                        isOwner = false,
                    )
                every { fixture.collectionRepository.observeCollections() } returns
                    flowOf(listOf(systemCollection, collection("c1", "Favorites")))
                every { fixture.collectionRepository.observeBookCollectionIds(any()) } returns flowOf(emptyList())
                val delegate = fixture.build(this)

                delegate.loadCollections("book-1")
                advanceUntilIdle()

                fixture.state.value.allCollections
                    .map { it.id } shouldContainExactly listOf("c1")
            }
        }
    })
