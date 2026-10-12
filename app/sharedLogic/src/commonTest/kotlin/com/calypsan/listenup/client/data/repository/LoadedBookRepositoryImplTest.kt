package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.client.TestData
import com.calypsan.listenup.client.domain.model.LoadedBook
import com.calypsan.listenup.client.playback.EnginePhase
import com.calypsan.listenup.client.test.fake.FakeBookRepository
import com.calypsan.listenup.client.test.fake.FakeLoadedBookStore
import com.calypsan.listenup.client.test.fake.FakePlaybackPositionRepository
import com.calypsan.listenup.client.test.fake.FakePlayerEngine
import com.calypsan.listenup.client.test.fake.positionFor
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class LoadedBookRepositoryImplTest :
    FunSpec({
        class Rig(
            stored: String? = null,
        ) {
            val store = FakeLoadedBookStore(stored)
            val books = FakeBookRepository()
            val positions = FakePlaybackPositionRepository()
            val engine = FakePlayerEngine()
            val repository = LoadedBookRepositoryImpl(store, books, positions, engine)
        }

        test("a fresh install is Restoring, then NothingLoaded, from local storage alone") {
            runTest {
                val rig = Rig()
                rig.repository.observe().test {
                    awaitItem() shouldBe LoadedBook.Restoring
                    awaitItem() shouldBe LoadedBook.NothingLoaded
                }
                rig.store.reads shouldBe 1
            }
        }

        test("a persisted id with a Room row restores as Loaded, paused, not finished") {
            runTest {
                val rig = Rig(stored = "b1")
                rig.books.setBookDetail(TestData.bookDetail(id = "b1"))
                rig.repository.observe().test {
                    awaitItem() shouldBe LoadedBook.Restoring
                    awaitItem() shouldBe LoadedBook.Loaded(bookId = "b1", isPlaying = false, isFinished = false)
                }
            }
        }

        test("a persisted id whose row is gone reads as NothingLoaded") {
            runTest {
                val rig = Rig(stored = "gone")
                rig.repository.observe().test {
                    awaitItem() shouldBe LoadedBook.Restoring
                    awaitItem() shouldBe LoadedBook.NothingLoaded
                }
            }
        }

        test("it emits on play/pause and on finish, and never on a position tick") {
            runTest {
                val rig = Rig(stored = "b1")
                rig.books.setBookDetail(TestData.bookDetail(id = "b1"))
                rig.positions.setPosition(positionFor("b1", positionMs = 1_000))
                rig.repository.observe().test {
                    awaitItem() shouldBe LoadedBook.Restoring
                    awaitItem() shouldBe LoadedBook.Loaded("b1", isPlaying = false, isFinished = false)
                    rig.engine.loadedBookId.value = BookId("b1")
                    rig.engine.phase.value = EnginePhase.PLAYING
                    awaitItem() shouldBe LoadedBook.Loaded("b1", isPlaying = true, isFinished = false)
                    rig.positions.setPosition(positionFor("b1", positionMs = 11_000))
                    rig.engine.positionMs.value = 11_000
                    expectNoEvents()
                    rig.engine.phase.value = EnginePhase.PAUSED
                    awaitItem() shouldBe LoadedBook.Loaded("b1", isPlaying = false, isFinished = false)
                    rig.positions.setPosition(positionFor("b1", positionMs = 5_400_000, isFinished = true))
                    awaitItem() shouldBe LoadedBook.Loaded("b1", isPlaying = false, isFinished = true)
                }
            }
        }

        test("setLoaded persists and emits; clear persists null") {
            runTest {
                val rig = Rig()
                rig.books.setBookDetail(TestData.bookDetail(id = "b2"))
                rig.repository.setLoaded(BookId("b2"))
                rig.store.stored shouldBe "b2"
                rig.repository.loadedBookId() shouldBe BookId("b2")
                rig.repository.clear()
                rig.store.stored shouldBe null
                rig.repository.loadedBookId() shouldBe null
            }
        }

        test("the engine playing a different book does not count as the loaded book playing") {
            runTest {
                val rig = Rig(stored = "b1")
                rig.books.setBookDetail(TestData.bookDetail(id = "b1"))
                rig.engine.loadedBookId.value = BookId("other")
                rig.engine.phase.value = EnginePhase.PLAYING
                rig.repository.observe().test {
                    awaitItem() shouldBe LoadedBook.Restoring
                    awaitItem() shouldBe LoadedBook.Loaded("b1", isPlaying = false, isFinished = false)
                }
            }
        }
    })
