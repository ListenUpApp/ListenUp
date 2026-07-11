package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.api.ImportService
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.imports.ImportAnalysis
import com.calypsan.listenup.api.dto.imports.ImportEvent
import com.calypsan.listenup.api.dto.imports.ImportResult
import com.calypsan.listenup.api.dto.imports.ImportStatus
import com.calypsan.listenup.api.dto.imports.ImportSummary
import com.calypsan.listenup.api.error.ImportError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.AbsItemId
import com.calypsan.listenup.core.AbsUserId
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ImportId
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for [ImportRepositoryImpl].
 *
 * Uses mokkery to mock [ImportService] — matching the pattern in [BackupRepositoryImplTest].
 * Each test verifies that:
 *  - suspend methods forward to the service and convert [WireAppResult] → [AppResult], and
 *  - [observeProgress] unwraps [RpcEvent.Data] into bare [ImportEvent]s while silently
 *    dropping [RpcEvent.Error] and [RpcEvent.Complete].
 */
class ImportRepositoryImplTest :
    FunSpec({

        // ── helpers ──────────────────────────────────────────────────────────────

        val importId = ImportId("abs-1")

        fun stubSummary(id: String = "abs-1") =
            ImportSummary(
                id = ImportId(id),
                createdAt = 1_000L,
                status = ImportStatus.ANALYZED,
                bookCount = 10,
                userCount = 2,
            )

        fun stubAnalysis() =
            ImportAnalysis(
                userMatches = emptyList(),
                bookMatchCounts = emptyMap(),
                ambiguous = emptyList(),
                unmatched = emptyList(),
            )

        fun stubResult() = ImportResult(importedCount = 8, booksNotInLibrary = 3, perUser = emptyMap())

        /** upload is not under test here — a relaxed ApiClientFactory mock stands in. */
        fun buildRepo(service: ImportService): ImportRepositoryImpl =
            ImportRepositoryImpl(
                channel = RpcChannel.forTest(service),
                clientFactory = mock(MockMode.autofill),
            )

        // ── analyze ───────────────────────────────────────────────────────────

        test("analyze returns Success wrapping the ImportAnalysis on wire success") {
            runTest {
                val analysis = stubAnalysis()
                val svc = mock<ImportService>()
                everySuspend { svc.analyze(importId) } returns WireAppResult.Success(analysis)

                val result = buildRepo(svc).analyze(importId)

                result.shouldBeInstanceOf<AppResult.Success<ImportAnalysis>>()
                result.data shouldBe analysis
            }
        }

        test("analyze returns Failure on wire failure") {
            runTest {
                val svc = mock<ImportService>()
                val error = ImportError.AnalysisFailed()
                everySuspend { svc.analyze(importId) } returns WireAppResult.Failure(error)

                val result = buildRepo(svc).analyze(importId)

                result.shouldBeInstanceOf<AppResult.Failure>()
                result.error shouldBe error
            }
        }

        // ── confirmMapping ──────────────────────────────────────────────────────

        test("confirmMapping forwards mappings and returns Success(Unit) on wire success") {
            runTest {
                val userMappings = mapOf(AbsUserId("u1") to UserId("lu1"))
                val bookOverrides = mapOf(AbsItemId("i1") to BookId("b1"), AbsItemId("i2") to null)
                val svc = mock<ImportService>()
                everySuspend {
                    svc.confirmMapping(importId, userMappings, bookOverrides)
                } returns WireAppResult.Success(Unit)

                val result = buildRepo(svc).confirmMapping(importId, userMappings, bookOverrides)

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
            }
        }

        test("confirmMapping returns Failure on wire failure") {
            runTest {
                val svc = mock<ImportService>()
                val error = ImportError.MappingInvalid()
                everySuspend {
                    svc.confirmMapping(importId, emptyMap(), emptyMap())
                } returns WireAppResult.Failure(error)

                val result = buildRepo(svc).confirmMapping(importId, emptyMap(), emptyMap())

                result.shouldBeInstanceOf<AppResult.Failure>()
                result.error shouldBe error
            }
        }

        // ── apply ─────────────────────────────────────────────────────────────

        test("apply returns Success wrapping the ImportResult on wire success") {
            runTest {
                val applyResult = stubResult()
                val svc = mock<ImportService>()
                everySuspend { svc.apply(importId) } returns WireAppResult.Success(applyResult)

                val result = buildRepo(svc).apply(importId)

                result.shouldBeInstanceOf<AppResult.Success<ImportResult>>()
                result.data shouldBe applyResult
            }
        }

        // ── listImports ─────────────────────────────────────────────────────────

        test("listImports returns Success wrapping the list on wire success") {
            runTest {
                val summaries = listOf(stubSummary("abs-1"), stubSummary("abs-2"))
                val svc = mock<ImportService>()
                everySuspend { svc.listImports() } returns WireAppResult.Success(summaries)

                val result = buildRepo(svc).listImports()

                result.shouldBeInstanceOf<AppResult.Success<List<ImportSummary>>>()
                result.data shouldBe summaries
            }
        }

        // ── deleteImport ────────────────────────────────────────────────────────

        test("deleteImport returns AppResult.Success(Unit) on wire success") {
            runTest {
                val svc = mock<ImportService>()
                everySuspend { svc.deleteImport(importId) } returns WireAppResult.Success(Unit)

                val result = buildRepo(svc).deleteImport(importId)

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
            }
        }

        test("deleteImport returns Failure on wire failure") {
            runTest {
                val svc = mock<ImportService>()
                val error = ImportError.ImportNotFound()
                everySuspend { svc.deleteImport(ImportId("abs-missing")) } returns WireAppResult.Failure(error)

                val result = buildRepo(svc).deleteImport(ImportId("abs-missing"))

                result.shouldBeInstanceOf<AppResult.Failure>()
                result.error shouldBe error
            }
        }

        // ── observeProgress ───────────────────────────────────────────────────

        test("observeProgress unwraps RpcEvent.Data into bare ImportEvents") {
            runTest {
                val hotFlow = MutableSharedFlow<RpcEvent<ImportEvent>>()
                val svc = mock<ImportService>()
                every { svc.observeProgress(importId) } returns hotFlow

                buildRepo(svc).observeProgress(importId).test {
                    hotFlow.emit(RpcEvent.Data(ImportEvent.Parsing))
                    awaitItem() shouldBe ImportEvent.Parsing

                    hotFlow.emit(RpcEvent.Data(ImportEvent.Matching(3, 10)))
                    awaitItem() shouldBe ImportEvent.Matching(3, 10)

                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeProgress silently drops RpcEvent.Error and RpcEvent.Complete") {
            runTest {
                val progressFlow =
                    flow {
                        emit(RpcEvent.Data<ImportEvent>(ImportEvent.Parsing))
                        emit(RpcEvent.Error(InternalError()))
                        emit(RpcEvent.Complete)
                        emit(RpcEvent.Data(ImportEvent.Applied(ImportResult(importedCount = 8, sessionsImported = 5, booksNotInLibrary = 3, perUser = emptyMap()))))
                    }

                val svc = mock<ImportService>()
                every { svc.observeProgress(importId) } returns progressFlow

                val events = mutableListOf<ImportEvent>()
                buildRepo(svc).observeProgress(importId).collect { events.add(it) }

                events shouldBe listOf(ImportEvent.Parsing, ImportEvent.Applied(ImportResult(importedCount = 8, sessionsImported = 5, booksNotInLibrary = 3, perUser = emptyMap())))
            }
        }
    })
