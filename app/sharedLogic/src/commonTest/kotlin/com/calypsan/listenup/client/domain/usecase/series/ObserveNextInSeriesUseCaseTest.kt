package com.calypsan.listenup.client.domain.usecase.series

import app.cash.turbine.test
import com.calypsan.listenup.client.TestData
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.client.test.fake.FakeBookRepository
import com.calypsan.listenup.client.test.fake.FakeInboxRepository
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

class ObserveNextInSeriesUseCaseTest :
    FunSpec({
        val book1 =
            TestData.bookListItem(
                id = "b1",
                title = "The Way of Kings",
                seriesId = "s1",
                seriesName = "Stormlight",
                seriesSequence = 1.0,
            )
        val book2 =
            TestData.bookListItem(
                id = "b2",
                title = "Words of Radiance",
                seriesId = "s1",
                seriesName = "Stormlight",
                seriesSequence = 2.0,
                duration = 172_800_000,
            )
        val series =
            SeriesWithBooks(
                series = TestData.series(id = "s1", name = "Stormlight"),
                books = listOf(book2, book1),
                bookSequences =
                    mapOf("b1" to 1.0, "b2" to 2.0),
            )

        fun rig(
            seriesFlow: MutableStateFlow<SeriesWithBooks?>,
        ): Triple<ObserveNextInSeriesUseCase, FakeBookRepository, FakeInboxRepository> {
            val books = FakeBookRepository()
            books.setBookDetail(TestData.bookDetail(id = "b1", seriesId = "s1", seriesName = "Stormlight", seriesSequence = 1.0))
            val inbox = FakeInboxRepository()
            val seriesRepository = mock<SeriesRepository> { every { observeSeriesWithBooks(any()) } returns seriesFlow }
            return Triple(ObserveNextInSeriesUseCase(books, seriesRepository, inbox), books, inbox)
        }

        test("the next book by sequence, with what Home and Now Playing draw") {
            runTest {
                val (useCase, _, _) = rig(MutableStateFlow(series))
                useCase("b1").test {
                    val next = awaitItem()!!
                    next.bookId shouldBe "b2"
                    next.title shouldBe "Words of Radiance"
                    next.seriesName shouldBe "Stormlight"
                    next.sequenceLabel shouldBe "2"
                    next.durationMs shouldBe 172_800_000
                }
            }
        }

        test("the last book in a series has no next") {
            runTest {
                val (useCase, books, _) = rig(MutableStateFlow(series))
                books.setBookDetail(TestData.bookDetail(id = "b2", seriesId = "s1", seriesName = "Stormlight", seriesSequence = 2.0))
                useCase("b2").test { awaitItem().shouldBeNull() }
            }
        }

        test("a held next book counts as none (S11), and comes back when released") {
            runTest {
                val (useCase, _, inbox) = rig(MutableStateFlow(series))
                inbox.hold("b2")
                useCase("b1").test {
                    awaitItem().shouldBeNull()
                    inbox.held.value = emptySet()
                    awaitItem()!!.bookId shouldBe "b2"
                }
            }
        }

        test("a book in no series has no next") {
            runTest {
                val (useCase, books, _) = rig(MutableStateFlow(null))
                books.setBookDetail(TestData.bookDetail(id = "solo"))
                useCase("solo").test { awaitItem().shouldBeNull() }
            }
        }
    })
