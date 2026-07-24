package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.GenreService
import com.calypsan.listenup.api.dto.FacetStats
import com.calypsan.listenup.api.dto.GenreSummary
import com.calypsan.listenup.api.error.GenreError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.client.data.local.db.GenreDao
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.test.fake.noopOfflineEditor
import com.calypsan.listenup.core.GenreId
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.everySuspend
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for [GenreRepositoryImpl]'s still-online curator-mutation surface (`createGenre`,
 * `moveGenre`, `mergeGenres`) — each routes through the bounded, self-healing [RpcChannel.call]
 * boundary instead of a hand-rolled `try/catch`. These pin the three outcomes of that boundary: a
 * value returns, a business `AppResult.Failure` passes straight through, and a thrown transport fault
 * becomes a typed `AppResult.Failure`. [RpcChannel.forTest] routes the dispatch through the REAL
 * [com.calypsan.listenup.client.data.remote.catchingRpcResult] fold, so the repo exercises production
 * fold semantics without a live socket. The offline-first surfaces (`updateGenre`, `deleteGenre`) are
 * covered by `GenreRepositoryOfflineTest` against a real in-memory database.
 */
class GenreRepositoryImplTest :
    FunSpec({

        fun repo(
            dao: GenreDao = mock(MockMode.autofill),
            service: GenreService = mock(),
        ): GenreRepositoryImpl = GenreRepositoryImpl(dao = dao, channel = RpcChannel.forTest(service), offlineEditor = noopOfflineEditor())

        test("createGenre dispatches to the service and returns the new id") {
            runTest {
                val service = mock<GenreService>()
                everySuspend { service.createGenre(null, "Sci-Fi", 0) } returns
                    WireAppResult.Success(GenreId("g-new"))

                val result = repo(service = service).createGenre(name = "Sci-Fi", parentId = null, sortOrder = 0)

                val success = result.shouldBeInstanceOf<AppResult.Success<GenreId>>()
                success.data shouldBe GenreId("g-new")
            }
        }

        test("createGenre propagates a business failure straight through (no reconnect)") {
            runTest {
                val service = mock<GenreService>()
                everySuspend { service.createGenre(null, "Dup", 0) } returns
                    WireAppResult.Failure(GenreError.SlugConflict())

                val result = repo(service = service).createGenre(name = "Dup", parentId = null, sortOrder = 0)

                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.error.shouldBeInstanceOf<GenreError.SlugConflict>()
            }
        }

        test("createGenre maps a thrown transport fault to a typed AppResult.Failure") {
            runTest {
                val service = mock<GenreService>()
                everySuspend { service.createGenre(null, "Boom", 0) } throws RuntimeException("socket died")

                val result = repo(service = service).createGenre(name = "Boom", parentId = null, sortOrder = 0)

                result.shouldBeInstanceOf<AppResult.Failure>()
            }
        }

        test("moveGenre dispatches to the service") {
            runTest {
                val service = mock<GenreService>()
                everySuspend { service.moveGenre(GenreId("g1"), GenreId("g2")) } returns WireAppResult.Success(Unit)

                repo(service = service).moveGenre(GenreId("g1"), GenreId("g2")).shouldBeInstanceOf<AppResult.Success<*>>()

                verifySuspend(VerifyMode.exactly(1)) { service.moveGenre(GenreId("g1"), GenreId("g2")) }
            }
        }

        test("mergeGenres dispatches to the service") {
            runTest {
                val service = mock<GenreService>()
                everySuspend { service.mergeGenres(GenreId("src"), GenreId("dst")) } returns WireAppResult.Success(Unit)

                repo(service = service).mergeGenres(GenreId("src"), GenreId("dst")).shouldBeInstanceOf<AppResult.Success<*>>()

                verifySuspend(VerifyMode.exactly(1)) { service.mergeGenres(GenreId("src"), GenreId("dst")) }
            }
        }

        test("getGenreStats dispatches to the service and returns the aggregate stats") {
            runTest {
                val service = mock<GenreService>()
                everySuspend { service.getGenreStats(GenreId("g1"), true) } returns
                    WireAppResult.Success(FacetStats(bookCount = 12, totalDurationMs = 3_600_000L))

                val result = repo(service = service).getGenreStats(GenreId("g1"), includeDescendants = true)

                val success = result.shouldBeInstanceOf<AppResult.Success<FacetStats>>()
                success.data shouldBe FacetStats(bookCount = 12, totalDurationMs = 3_600_000L)
            }
        }

        test("getGenreBySlug dispatches to the service and maps GenreSummary to the domain Genre") {
            runTest {
                val service = mock<GenreService>()
                everySuspend { service.getGenreBySlug("sci-fi") } returns
                    WireAppResult.Success(
                        GenreSummary(id = GenreId("g1"), name = "Sci-Fi", slug = "sci-fi", path = "/sci-fi", bookCount = 4),
                    )

                val result = repo(service = service).getGenreBySlug("sci-fi")

                val success = result.shouldBeInstanceOf<AppResult.Success<Genre?>>()
                success.data shouldBe Genre(id = "g1", name = "Sci-Fi", slug = "sci-fi", path = "/sci-fi", bookCount = 4)
            }
        }

        test("getGenreBySlug returns null when the service finds no live genre") {
            runTest {
                val service = mock<GenreService>()
                everySuspend { service.getGenreBySlug("missing") } returns WireAppResult.Success(null)

                val result = repo(service = service).getGenreBySlug("missing")

                val success = result.shouldBeInstanceOf<AppResult.Success<Genre?>>()
                success.data shouldBe null
            }
        }
    })
