package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.MetadataLookupService
import com.calypsan.listenup.api.dto.MetadataApplySelection
import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.dto.MetadataChapters
import com.calypsan.listenup.api.dto.MetadataContributorHit
import com.calypsan.listenup.api.dto.MetadataContributorProfile
import com.calypsan.listenup.api.dto.MetadataSearchResults
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException

/**
 * Unit tests for [MetadataRepositoryImpl].
 *
 * Each of the 9 methods gets a success-path test confirming delegation to the
 * underlying RPC service. In addition, one method ("wrapper canary" = searchBooks)
 * gets the full trio: success, CancellationException rethrow, and arbitrary
 * Throwable → AppResult.Failure(TransportError).
 *
 * The wrapper logic is identical for all 9 methods — exhaustive duplication
 * would triple the test count without adding coverage.
 *
 * Wire [WireAppResult] is the return type of [MetadataLookupService] methods.
 * [AppResult] is the return type of [MetadataRepositoryImpl] methods — the repo
 * converts at the boundary.
 */
class MetadataRepositoryImplTest :
    FunSpec({

        fun buildRepo(service: MetadataLookupService): MetadataRepositoryImpl = MetadataRepositoryImpl(RpcChannel.forTest(service))

        // ── Wrapper canary — searchBooks ──────────────────────────────────────────

        test("searchBooks delegates to service and returns Success") {
            val payload = MetadataSearchResults(emptyList())
            val service = mock<MetadataLookupService>()
            everySuspend { service.searchBooks("query", MetadataLocale.DEFAULT) } returns WireAppResult.Success(payload)

            val result = buildRepo(service).searchBooks("query", MetadataLocale.DEFAULT)

            result shouldBe AppResult.Success(payload)
        }

        test("searchBooks re-throws CancellationException") {
            val service = mock<MetadataLookupService>()
            everySuspend { service.searchBooks(any(), any()) } throws CancellationException("cancelled")

            shouldThrow<CancellationException> {
                buildRepo(service).searchBooks("q", MetadataLocale.DEFAULT)
            }
        }

        test("searchBooks wraps arbitrary Throwable into AppResult.Failure") {
            val service = mock<MetadataLookupService>()
            everySuspend { service.searchBooks(any(), any()) } throws RuntimeException("boom")

            val result = buildRepo(service).searchBooks("q", null)

            result.shouldBeInstanceOf<AppResult.Failure>()
            result.error.shouldBeInstanceOf<InternalError>()
        }

        // ── Success-path per method ───────────────────────────────────────────────

        test("getBookMetadata delegates to service and returns Success") {
            val service = mock<MetadataLookupService>()
            everySuspend { service.getBookMetadata("B001", MetadataLocale.DEFAULT) } returns WireAppResult.Success<MetadataBook?>(null)

            buildRepo(service).getBookMetadata("B001", MetadataLocale.DEFAULT) shouldBe AppResult.Success<MetadataBook?>(null)
        }

        test("getBookChapters delegates to service and returns Success") {
            val service = mock<MetadataLookupService>()
            everySuspend { service.getBookChapters("B001", MetadataLocale.DEFAULT) } returns WireAppResult.Success<MetadataChapters?>(null)

            buildRepo(service).getBookChapters("B001", MetadataLocale.DEFAULT) shouldBe AppResult.Success<MetadataChapters?>(null)
        }

        test("searchContributorMetadata delegates to service and returns Success") {
            val payload = emptyList<MetadataContributorHit>()
            val service = mock<MetadataLookupService>()
            everySuspend { service.searchContributorMetadata("query") } returns WireAppResult.Success(payload)

            buildRepo(service).searchContributorMetadata("query") shouldBe AppResult.Success(payload)
        }

        test("getContributorMetadata delegates to service and returns Success") {
            val service = mock<MetadataLookupService>()
            everySuspend { service.getContributorMetadata("A001", MetadataLocale.DEFAULT) } returns WireAppResult.Success<MetadataContributorProfile?>(null)

            buildRepo(service).getContributorMetadata("A001", MetadataLocale.DEFAULT) shouldBe AppResult.Success<MetadataContributorProfile?>(null)
        }

        test("refreshBookMetadata delegates to service and returns Success") {
            val service = mock<MetadataLookupService>()
            everySuspend { service.refreshBookMetadata("B001", MetadataLocale.DEFAULT) } returns WireAppResult.Success<MetadataBook?>(null)

            buildRepo(service).refreshBookMetadata("B001", MetadataLocale.DEFAULT) shouldBe AppResult.Success<MetadataBook?>(null)
        }

        test("applyBookMetadata delegates to service and returns Success") {
            val service = mock<MetadataLookupService>()
            val sel =
                MetadataApplySelection(
                    title = true,
                    subtitle = true,
                    description = true,
                    publisher = true,
                    releaseDate = true,
                    language = true,
                    cover = false,
                    authorAsins = emptySet(),
                    narratorAsins = emptySet(),
                    seriesAsins = emptySet(),
                )
            everySuspend { service.applyBookMetadata(BookId("b1"), "B001", MetadataLocale.DEFAULT, sel) } returns WireAppResult.Success(Unit)

            buildRepo(service).applyBookMetadata(BookId("b1"), "B001", MetadataLocale.DEFAULT, sel) shouldBe AppResult.Success(Unit)
        }

        test("applyContributorMetadata delegates to service and returns Success") {
            val service = mock<MetadataLookupService>()
            everySuspend {
                service.applyContributorMetadata(ContributorId("c1"), "A001", MetadataLocale.DEFAULT)
            } returns WireAppResult.Success(Unit)

            buildRepo(service)
                .applyContributorMetadata(ContributorId("c1"), "A001", MetadataLocale.DEFAULT) shouldBe AppResult.Success(Unit)
        }

        test("applyChapterNames delegates to service and returns Success") {
            val service = mock<MetadataLookupService>()
            everySuspend {
                service.applyChapterNames(BookId("b1"), "B001", MetadataLocale.DEFAULT, setOf(0, 2))
            } returns WireAppResult.Success(Unit)

            buildRepo(service).applyChapterNames(BookId("b1"), "B001", MetadataLocale.DEFAULT, setOf(0, 2)) shouldBe
                AppResult.Success(Unit)
        }
    })
