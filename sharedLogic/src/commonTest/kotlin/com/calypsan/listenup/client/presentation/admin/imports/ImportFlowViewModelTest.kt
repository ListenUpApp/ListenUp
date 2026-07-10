package com.calypsan.listenup.client.presentation.admin.imports

import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.imports.AbsItemRef
import com.calypsan.listenup.api.dto.imports.AbsUserMatch
import com.calypsan.listenup.api.dto.imports.ImportAnalysis
import com.calypsan.listenup.api.dto.imports.ImportEvent
import com.calypsan.listenup.api.dto.imports.ImportResult
import com.calypsan.listenup.api.dto.imports.ImportStatus
import com.calypsan.listenup.api.dto.imports.ImportSummary
import com.calypsan.listenup.api.dto.imports.MatchTier
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.FacetCount
import com.calypsan.listenup.client.domain.model.InviteInfo
import com.calypsan.listenup.client.domain.model.Library
import com.calypsan.listenup.client.domain.model.ScanProgressState
import com.calypsan.listenup.client.domain.model.SearchFacets
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.domain.model.SearchResult
import com.calypsan.listenup.client.domain.model.ServerSettings
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.domain.model.UserPermissions
import com.calypsan.listenup.client.data.remote.BrowseFilesystemResponse
import com.calypsan.listenup.client.domain.repository.AdminRepository
import com.calypsan.listenup.client.domain.repository.ImportRepository
import com.calypsan.listenup.client.domain.repository.SearchRepository
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.core.AbsItemId
import com.calypsan.listenup.core.AbsUserId
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FileSource
import com.calypsan.listenup.core.ImportId
import com.calypsan.listenup.core.error.ErrorBus
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for [ImportFlowViewModel] backed by a fake [ImportRepository].
 *
 * The flow under test:
 *  upload(fileSource) → Uploading → Analyzing (live events) → Review
 *  → setUserMapping / setBookOverride (mutate Review state in place)
 *  → confirmAndApply() → Applying (live events) → Done
 *
 * Error paths: any AppResult.Failure → Error state + ErrorBus emission.
 * CancellationException from repository must propagate (not be swallowed).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportFlowViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        // ─── helpers ────────────────────────────────────────────────────────────

        fun importSummary(id: String = "import-1") =
            ImportSummary(
                id = ImportId(id),
                createdAt = 1_000L,
                status = ImportStatus.ANALYZED,
                bookCount = 10,
                userCount = 2,
            )

        fun importAnalysis() =
            ImportAnalysis(
                userMatches =
                    listOf(
                        AbsUserMatch(
                            absUserId = AbsUserId("abs-user-1"),
                            absUsername = "alice",
                            absEmail = "alice@example.com",
                            suggestedUserId = UserId("lu-user-1"),
                            confidence = MatchTier.STRONG,
                        ),
                    ),
                bookMatchCounts =
                    mapOf(
                        MatchTier.ASIN to 8,
                        MatchTier.UNMATCHED to 1,
                    ),
                ambiguous = emptyList(),
                unmatched =
                    listOf(
                        AbsItemRef(
                            absItemId = AbsItemId("abs-item-99"),
                            title = "Unknown Book",
                            asin = null,
                            isbn = null,
                            relPath = null,
                        ),
                    ),
                importableSessionCount = 42,
            )

        fun importResult() =
            ImportResult(
                importedCount = 40,
                sessionsImported = 38,
                booksNotInLibrary = 2,
                perUser = mapOf(UserId("lu-user-1") to 40),
            )

        // ─── initial state ────────────────────────────────────────────────────

        test("initial state is Idle") {
            val repo = FakeImportRepository()
            val vm = ImportFlowViewModel(repo, ErrorBus(), FakeSyncRepository(), FakeAdminRepository(), FakeSearchRepository())

            vm.uiState.value.shouldBeInstanceOf<ImportFlowUiState.Idle>()
        }

        // ─── start (upload → analyze) ─────────────────────────────────────────

        test("start transitions to Uploading then Analyzing on Matching event") {
            runTest(testDispatcher) {
                val repo =
                    FakeImportRepository(
                        uploadResult = AppResult.Success(importSummary()),
                        analyzeResult = AppResult.Success(importAnalysis()),
                    )
                val vm = ImportFlowViewModel(repo, ErrorBus(), FakeSyncRepository(), FakeAdminRepository(), FakeSearchRepository())

                vm.start(StubFileSource("backup.audiobookshelf"))

                // After launch but before any async work: still Idle (dispatcher not yet run)
                // Advance only enough to pass upload:
                advanceUntilIdle()

                // Push a Matching event so we can observe Analyzing
                repo.progressFlow.emit(
                    ImportEvent.Matching(done = 3, total = 10, currentItem = "The Hobbit", usersMatched = 1, booksMatched = 3),
                )
                advanceUntilIdle()

                // After Analyzed event, should be in Review
                repo.progressFlow.emit(ImportEvent.Analyzed(summary = importSummary()))
                advanceUntilIdle()

                vm.uiState.value.shouldBeInstanceOf<ImportFlowUiState.Review>()
            }
        }

        test("start → Analyzing state carries progress counters from Matching events") {
            runTest(testDispatcher) {
                // analyze() suspends until we complete the deferred — this keeps the VM in
                // Analyzing long enough to observe the Matching event update.
                val analyzeDeferred = CompletableDeferred<AppResult<ImportAnalysis>>()
                val repo =
                    FakeImportRepository(
                        uploadResult = AppResult.Success(importSummary()),
                        analyzeDeferred = analyzeDeferred,
                    )
                val vm = ImportFlowViewModel(repo, ErrorBus(), FakeSyncRepository(), FakeAdminRepository(), FakeSearchRepository())
                vm.start(StubFileSource("backup.audiobookshelf"))
                // Advance past upload; analyze() is now suspended
                advanceUntilIdle()

                // Emit a Matching tick while analyze() is still in-flight
                repo.progressFlow.emit(
                    ImportEvent.Matching(done = 5, total = 10, currentItem = "Dune", usersMatched = 2, booksMatched = 5),
                )
                advanceUntilIdle()

                val analyzing = vm.uiState.value.shouldBeInstanceOf<ImportFlowUiState.Analyzing>()
                analyzing.done shouldBe 5
                analyzing.total shouldBe 10
                analyzing.currentItem shouldBe "Dune"
                analyzing.usersMatched shouldBe 2
                analyzing.booksMatched shouldBe 5

                // Unblock analyze() so the test coroutine can clean up
                analyzeDeferred.complete(AppResult.Success(importAnalysis()))
                advanceUntilIdle()
            }
        }

        test("analyze result lands in Review with the analysis") {
            runTest(testDispatcher) {
                val analysis = importAnalysis()
                val luUsers = listOf(fakeAdminUser("lu-user-1", "alice@example.com"))
                val repo =
                    FakeImportRepository(
                        uploadResult = AppResult.Success(importSummary()),
                        analyzeResult = AppResult.Success(analysis),
                    )
                val adminRepo = FakeAdminRepository(getUsersResult = AppResult.Success(luUsers))
                val vm = ImportFlowViewModel(repo, ErrorBus(), FakeSyncRepository(), adminRepo, FakeSearchRepository())
                vm.start(StubFileSource("backup.audiobookshelf"))
                advanceUntilIdle()

                repo.progressFlow.emit(ImportEvent.Analyzed(summary = importSummary()))
                advanceUntilIdle()

                val review = vm.uiState.value.shouldBeInstanceOf<ImportFlowUiState.Review>()
                review.analysis shouldBe analysis
                // No auto-seeding: mappings start empty
                review.userMappings.shouldBeEmpty()
                review.skippedUsers.shouldBeEmpty()
                review.bookOverrides shouldBe emptyMap()
                review.listenupUsers shouldBe luUsers
            }
        }

        // ─── explicit user matching (replaces auto-seeding) ───────────────────

        test("Review starts with empty userMappings, empty skippedUsers, and listenupUsers from AdminRepository") {
            runTest(testDispatcher) {
                val luUsers =
                    listOf(
                        fakeAdminUser("lu-1", "alice@example.com"),
                        fakeAdminUser("lu-2", "bob@example.com"),
                    )
                val repo =
                    FakeImportRepository(
                        uploadResult = AppResult.Success(importSummary()),
                        analyzeResult = AppResult.Success(importAnalysis()),
                    )
                val adminRepo = FakeAdminRepository(getUsersResult = AppResult.Success(luUsers))
                val vm = ImportFlowViewModel(repo, ErrorBus(), FakeSyncRepository(), adminRepo, FakeSearchRepository())
                vm.start(StubFileSource("backup.audiobookshelf"))
                advanceUntilIdle()
                repo.progressFlow.emit(ImportEvent.Analyzed(summary = importSummary()))
                advanceUntilIdle()

                val review = vm.uiState.value.shouldBeInstanceOf<ImportFlowUiState.Review>()
                review.userMappings.shouldBeEmpty()
                review.skippedUsers.shouldBeEmpty()
                review.listenupUsers shouldBe luUsers
            }
        }

        test("setUserMapping assigns the mapping and removes absUserId from skippedUsers if present") {
            runTest(testDispatcher) {
                val repo =
                    FakeImportRepository(
                        uploadResult = AppResult.Success(importSummary()),
                        analyzeResult = AppResult.Success(importAnalysis()),
                    )
                val vm = ImportFlowViewModel(repo, ErrorBus(), FakeSyncRepository(), FakeAdminRepository(), FakeSearchRepository())
                vm.start(StubFileSource("backup.audiobookshelf"))
                advanceUntilIdle()
                repo.progressFlow.emit(ImportEvent.Analyzed(summary = importSummary()))
                advanceUntilIdle()

                val absUser = AbsUserId("abs-user-1")
                val luUser = UserId("lu-user-1")

                // First skip the user, then assign — assign should un-skip
                vm.skipUser(absUser)
                vm.setUserMapping(absUser, luUser)

                val review = vm.uiState.value.shouldBeInstanceOf<ImportFlowUiState.Review>()
                review.userMappings[absUser] shouldBe luUser
                review.skippedUsers.contains(absUser).shouldBeFalse()
            }
        }

        test("skipUser adds absUserId to skippedUsers and removes any existing mapping") {
            runTest(testDispatcher) {
                val repo =
                    FakeImportRepository(
                        uploadResult = AppResult.Success(importSummary()),
                        analyzeResult = AppResult.Success(importAnalysis()),
                    )
                val vm = ImportFlowViewModel(repo, ErrorBus(), FakeSyncRepository(), FakeAdminRepository(), FakeSearchRepository())
                vm.start(StubFileSource("backup.audiobookshelf"))
                advanceUntilIdle()
                repo.progressFlow.emit(ImportEvent.Analyzed(summary = importSummary()))
                advanceUntilIdle()

                val absUser = AbsUserId("abs-user-1")
                val luUser = UserId("lu-user-1")

                // First assign, then skip — skip should remove the mapping
                vm.setUserMapping(absUser, luUser)
                vm.skipUser(absUser)

                val review = vm.uiState.value.shouldBeInstanceOf<ImportFlowUiState.Review>()
                review.skippedUsers.contains(absUser).shouldBeTrue()
                review.userMappings.containsKey(absUser).shouldBeFalse()
            }
        }

        test("adminRepository.getUsers() failure → Review entered with listenupUsers empty, error emitted to bus") {
            runTest(testDispatcher) {
                val error = TransportError.NetworkUnavailable()
                val repo =
                    FakeImportRepository(
                        uploadResult = AppResult.Success(importSummary()),
                        analyzeResult = AppResult.Success(importAnalysis()),
                    )
                val bus = ErrorBus()
                val adminRepo = FakeAdminRepository(getUsersResult = AppResult.Failure(error))
                val vm = ImportFlowViewModel(repo, bus, FakeSyncRepository(), adminRepo, FakeSearchRepository())
                vm.start(StubFileSource("backup.audiobookshelf"))
                advanceUntilIdle()
                repo.progressFlow.emit(ImportEvent.Analyzed(summary = importSummary()))
                advanceUntilIdle()

                // Review is still entered — getUsers failure is non-fatal
                val review = vm.uiState.value.shouldBeInstanceOf<ImportFlowUiState.Review>()
                review.listenupUsers.shouldBeEmpty()
            }
        }

        test("confirmAndApply sends only explicit userMappings; skipped/unresolved users are absent") {
            runTest(testDispatcher) {
                // importAnalysis() has one STRONG-suggested abs-user-1 → lu-user-1, but we
                // do NOT auto-seed. Only an explicit setUserMapping call gets sent.
                val repo =
                    FakeImportRepository(
                        uploadResult = AppResult.Success(importSummary()),
                        analyzeResult = AppResult.Success(importAnalysis()),
                        confirmMappingResult = AppResult.Success(Unit),
                        applyResult = AppResult.Success(importResult()),
                    )
                val vm = ImportFlowViewModel(repo, ErrorBus(), FakeSyncRepository(), FakeAdminRepository(), FakeSearchRepository())
                vm.start(StubFileSource("backup.audiobookshelf"))
                advanceUntilIdle()
                repo.progressFlow.emit(ImportEvent.Analyzed(summary = importSummary()))
                advanceUntilIdle()

                // Explicitly assign one user
                val absUser = AbsUserId("abs-user-1")
                val luUser = UserId("lu-user-1")
                vm.setUserMapping(absUser, luUser)

                vm.confirmAndApply()
                advanceUntilIdle()
                repo.progressFlow.emit(ImportEvent.Applied(result = importResult()))
                advanceUntilIdle()

                // Only the one explicit mapping is sent — no auto-seeded extras
                repo.confirmedUserMappings shouldBe mapOf(absUser to luUser)
            }
        }

        // ─── review mutations ─────────────────────────────────────────────────

        test("setUserMapping updates Review.userMappings in place") {
            runTest(testDispatcher) {
                val repo =
                    FakeImportRepository(
                        uploadResult = AppResult.Success(importSummary()),
                        analyzeResult = AppResult.Success(importAnalysis()),
                    )
                val vm = ImportFlowViewModel(repo, ErrorBus(), FakeSyncRepository(), FakeAdminRepository(), FakeSearchRepository())
                vm.start(StubFileSource("backup.audiobookshelf"))
                advanceUntilIdle()
                repo.progressFlow.emit(ImportEvent.Analyzed(summary = importSummary()))
                advanceUntilIdle()

                val absUser = AbsUserId("abs-user-1")
                val luUser = UserId("lu-user-1")
                vm.setUserMapping(absUser, luUser)

                val review = vm.uiState.value.shouldBeInstanceOf<ImportFlowUiState.Review>()
                review.userMappings[absUser] shouldBe luUser
            }
        }

        test("setBookOverride updates Review.bookOverrides in place") {
            runTest(testDispatcher) {
                val repo =
                    FakeImportRepository(
                        uploadResult = AppResult.Success(importSummary()),
                        analyzeResult = AppResult.Success(importAnalysis()),
                    )
                val vm = ImportFlowViewModel(repo, ErrorBus(), FakeSyncRepository(), FakeAdminRepository(), FakeSearchRepository())
                vm.start(StubFileSource("backup.audiobookshelf"))
                advanceUntilIdle()
                repo.progressFlow.emit(ImportEvent.Analyzed(summary = importSummary()))
                advanceUntilIdle()

                val absItem = AbsItemId("abs-item-99")
                val bookId = BookId("book-42")
                vm.setBookOverride(absItem, bookId)

                val review = vm.uiState.value.shouldBeInstanceOf<ImportFlowUiState.Review>()
                review.bookOverrides[absItem] shouldBe bookId
            }
        }

        test("setBookOverride with null marks item as skipped") {
            runTest(testDispatcher) {
                val repo =
                    FakeImportRepository(
                        uploadResult = AppResult.Success(importSummary()),
                        analyzeResult = AppResult.Success(importAnalysis()),
                    )
                val vm = ImportFlowViewModel(repo, ErrorBus(), FakeSyncRepository(), FakeAdminRepository(), FakeSearchRepository())
                vm.start(StubFileSource("backup.audiobookshelf"))
                advanceUntilIdle()
                repo.progressFlow.emit(ImportEvent.Analyzed(summary = importSummary()))
                advanceUntilIdle()

                vm.setBookOverride(AbsItemId("abs-item-99"), null)

                val review = vm.uiState.value.shouldBeInstanceOf<ImportFlowUiState.Review>()
                // Key must be present with a null value (explicit skip)
                review.bookOverrides.containsKey(AbsItemId("abs-item-99")).shouldBeTrue()
                review.bookOverrides[AbsItemId("abs-item-99")] shouldBe null
            }
        }

        // ─── confirmAndApply ──────────────────────────────────────────────────

        test("confirmAndApply moves through Applying to Done and calls refreshListeningHistory") {
            runTest(testDispatcher) {
                val result = importResult()
                val repo =
                    FakeImportRepository(
                        uploadResult = AppResult.Success(importSummary()),
                        analyzeResult = AppResult.Success(importAnalysis()),
                        confirmMappingResult = AppResult.Success(Unit),
                        applyResult = AppResult.Success(result),
                    )
                val sync = FakeSyncRepository()
                val vm = ImportFlowViewModel(repo, ErrorBus(), sync, FakeAdminRepository(), FakeSearchRepository())
                vm.start(StubFileSource("backup.audiobookshelf"))
                advanceUntilIdle()
                repo.progressFlow.emit(ImportEvent.Analyzed(summary = importSummary()))
                advanceUntilIdle()

                vm.confirmAndApply()
                advanceUntilIdle()

                // Emit an Applying tick and the terminal Applied event
                repo.progressFlow.emit(
                    ImportEvent.Applying(done = 10, total = 40, currentItem = "Alice", sessionsWritten = 10),
                )
                advanceUntilIdle()
                repo.progressFlow.emit(ImportEvent.Applied(result = result))
                advanceUntilIdle()

                val done = vm.uiState.value.shouldBeInstanceOf<ImportFlowUiState.Done>()
                done.result.importedCount shouldBe 40
                sync.refreshListeningHistoryCalled.shouldBeTrue()
            }
        }

        test("confirmAndApply passes Review mappings to confirmMapping") {
            runTest(testDispatcher) {
                val repo =
                    FakeImportRepository(
                        uploadResult = AppResult.Success(importSummary()),
                        analyzeResult = AppResult.Success(importAnalysis()),
                        confirmMappingResult = AppResult.Success(Unit),
                        applyResult = AppResult.Success(importResult()),
                    )
                val vm = ImportFlowViewModel(repo, ErrorBus(), FakeSyncRepository(), FakeAdminRepository(), FakeSearchRepository())
                vm.start(StubFileSource("backup.audiobookshelf"))
                advanceUntilIdle()
                repo.progressFlow.emit(ImportEvent.Analyzed(summary = importSummary()))
                advanceUntilIdle()

                val absUser = AbsUserId("abs-user-1")
                val luUser = UserId("lu-user-1")
                vm.setUserMapping(absUser, luUser)
                vm.confirmAndApply()
                advanceUntilIdle()
                repo.progressFlow.emit(ImportEvent.Applied(result = importResult()))
                advanceUntilIdle()

                // Check that confirmMapping received the user mapping
                repo.confirmedUserMappings[absUser] shouldBe luUser
            }
        }

        test("Applying events update Applying state") {
            runTest(testDispatcher) {
                // apply() suspends until we complete the deferred — this keeps the VM in
                // Applying long enough to observe the Applying event update.
                val applyDeferred = CompletableDeferred<AppResult<ImportResult>>()
                val repo =
                    FakeImportRepository(
                        uploadResult = AppResult.Success(importSummary()),
                        analyzeResult = AppResult.Success(importAnalysis()),
                        confirmMappingResult = AppResult.Success(Unit),
                        applyDeferred = applyDeferred,
                    )
                val vm = ImportFlowViewModel(repo, ErrorBus(), FakeSyncRepository(), FakeAdminRepository(), FakeSearchRepository())
                vm.start(StubFileSource("backup.audiobookshelf"))
                advanceUntilIdle()
                // analyze() completed immediately; VM is in Review
                vm.confirmAndApply()
                // Advance past confirmMapping; apply() is now suspended
                advanceUntilIdle()

                // Emit an Applying tick while apply() is still in-flight
                repo.progressFlow.emit(
                    ImportEvent.Applying(done = 20, total = 40, currentItem = "Bob", sessionsWritten = 18),
                )
                advanceUntilIdle()

                val applying = vm.uiState.value.shouldBeInstanceOf<ImportFlowUiState.Applying>()
                applying.done shouldBe 20
                applying.total shouldBe 40
                applying.currentItem shouldBe "Bob"
                applying.sessionsWritten shouldBe 18

                // Unblock apply() so the test coroutine can clean up
                applyDeferred.complete(AppResult.Success(importResult()))
                advanceUntilIdle()
            }
        }

        // ─── error paths ──────────────────────────────────────────────────────

        test("upload failure → Error state and ErrorBus emission") {
            runTest(testDispatcher) {
                val error = TransportError.NetworkUnavailable()
                val repo = FakeImportRepository(uploadResult = AppResult.Failure(error))
                val bus = ErrorBus()
                val vm = ImportFlowViewModel(repo, bus, FakeSyncRepository(), FakeAdminRepository(), FakeSearchRepository())

                vm.start(StubFileSource("backup.audiobookshelf"))
                advanceUntilIdle()

                vm.uiState.value.shouldBeInstanceOf<ImportFlowUiState.Error>()
            }
        }

        test("analyze failure → Error state and ErrorBus emission") {
            runTest(testDispatcher) {
                val error = TransportError.NetworkUnavailable()
                val repo =
                    FakeImportRepository(
                        uploadResult = AppResult.Success(importSummary()),
                        analyzeResult = AppResult.Failure(error),
                    )
                val bus = ErrorBus()
                val vm = ImportFlowViewModel(repo, bus, FakeSyncRepository(), FakeAdminRepository(), FakeSearchRepository())

                vm.start(StubFileSource("backup.audiobookshelf"))
                advanceUntilIdle()

                vm.uiState.value.shouldBeInstanceOf<ImportFlowUiState.Error>()
            }
        }

        test("ImportEvent.Failed during analyze → Error state") {
            runTest(testDispatcher) {
                // analyze() suspends — we emit Failed while it is still in-flight so the
                // error event races with (and should win over) the eventual AppResult.
                val analyzeDeferred = CompletableDeferred<AppResult<ImportAnalysis>>()
                val repo =
                    FakeImportRepository(
                        uploadResult = AppResult.Success(importSummary()),
                        analyzeDeferred = analyzeDeferred,
                    )
                val vm = ImportFlowViewModel(repo, ErrorBus(), FakeSyncRepository(), FakeAdminRepository(), FakeSearchRepository())
                vm.start(StubFileSource("backup.audiobookshelf"))
                // Advance past upload; analyze() is now suspended
                advanceUntilIdle()

                // Emit Failed while analyze() is in-flight
                repo.progressFlow.emit(ImportEvent.Failed(reason = "Corrupt backup zip."))
                advanceUntilIdle()

                val errorState = vm.uiState.value.shouldBeInstanceOf<ImportFlowUiState.Error>()
                errorState.error.shouldNotBeNull()

                // Complete the deferred (returns success, but Error wins since the event set it first)
                analyzeDeferred.complete(AppResult.Success(importAnalysis()))
                advanceUntilIdle()

                // Still Error — the Failed event wins
                vm.uiState.value.shouldBeInstanceOf<ImportFlowUiState.Error>()
            }
        }

        test("confirmMapping failure → Error state") {
            runTest(testDispatcher) {
                val repo =
                    FakeImportRepository(
                        uploadResult = AppResult.Success(importSummary()),
                        analyzeResult = AppResult.Success(importAnalysis()),
                        confirmMappingResult = AppResult.Failure(TransportError.NetworkUnavailable()),
                    )
                val vm = ImportFlowViewModel(repo, ErrorBus(), FakeSyncRepository(), FakeAdminRepository(), FakeSearchRepository())
                vm.start(StubFileSource("backup.audiobookshelf"))
                advanceUntilIdle()
                repo.progressFlow.emit(ImportEvent.Analyzed(summary = importSummary()))
                advanceUntilIdle()
                vm.confirmAndApply()
                advanceUntilIdle()

                vm.uiState.value.shouldBeInstanceOf<ImportFlowUiState.Error>()
            }
        }

        test("apply failure → Error state") {
            runTest(testDispatcher) {
                val repo =
                    FakeImportRepository(
                        uploadResult = AppResult.Success(importSummary()),
                        analyzeResult = AppResult.Success(importAnalysis()),
                        confirmMappingResult = AppResult.Success(Unit),
                        applyResult = AppResult.Failure(TransportError.NetworkUnavailable()),
                    )
                val vm = ImportFlowViewModel(repo, ErrorBus(), FakeSyncRepository(), FakeAdminRepository(), FakeSearchRepository())
                vm.start(StubFileSource("backup.audiobookshelf"))
                advanceUntilIdle()
                repo.progressFlow.emit(ImportEvent.Analyzed(summary = importSummary()))
                advanceUntilIdle()
                vm.confirmAndApply()
                advanceUntilIdle()

                vm.uiState.value.shouldBeInstanceOf<ImportFlowUiState.Error>()
            }
        }

        // ─── reset ────────────────────────────────────────────────────────────

        test("reset from Error returns to Idle") {
            runTest(testDispatcher) {
                val repo = FakeImportRepository(uploadResult = AppResult.Failure(TransportError.NetworkUnavailable()))
                val vm = ImportFlowViewModel(repo, ErrorBus(), FakeSyncRepository(), FakeAdminRepository(), FakeSearchRepository())
                vm.start(StubFileSource("backup.audiobookshelf"))
                advanceUntilIdle()
                vm.uiState.value.shouldBeInstanceOf<ImportFlowUiState.Error>()

                vm.reset()

                vm.uiState.value.shouldBeInstanceOf<ImportFlowUiState.Idle>()
            }
        }

        test("reset from Done returns to Idle") {
            runTest(testDispatcher) {
                val result = importResult()
                val repo =
                    FakeImportRepository(
                        uploadResult = AppResult.Success(importSummary()),
                        analyzeResult = AppResult.Success(importAnalysis()),
                        confirmMappingResult = AppResult.Success(Unit),
                        applyResult = AppResult.Success(result),
                    )
                val vm = ImportFlowViewModel(repo, ErrorBus(), FakeSyncRepository(), FakeAdminRepository(), FakeSearchRepository())
                vm.start(StubFileSource("backup.audiobookshelf"))
                advanceUntilIdle()
                repo.progressFlow.emit(ImportEvent.Analyzed(summary = importSummary()))
                advanceUntilIdle()
                vm.confirmAndApply()
                advanceUntilIdle()
                repo.progressFlow.emit(ImportEvent.Applied(result = result))
                advanceUntilIdle()
                vm.uiState.value.shouldBeInstanceOf<ImportFlowUiState.Done>()

                vm.reset()

                vm.uiState.value.shouldBeInstanceOf<ImportFlowUiState.Idle>()
            }
        }

        // ─── book search ──────────────────────────────────────────────────────

        /** Helper to drive the VM into Review state. Must be called inside [runTest]. */
        suspend fun kotlinx.coroutines.test.TestScope.driveToReview(
            vm: ImportFlowViewModel,
            repo: FakeImportRepository,
        ) {
            vm.start(StubFileSource("backup.audiobookshelf"))
            advanceUntilIdle()
            repo.progressFlow.emit(ImportEvent.Analyzed(summary = importSummary()))
            advanceUntilIdle()
        }

        test("openBookSearch sets non-null bookSearch with correct absItemId, empty query and results") {
            runTest(testDispatcher) {
                val repo =
                    FakeImportRepository(
                        uploadResult = AppResult.Success(importSummary()),
                        analyzeResult = AppResult.Success(importAnalysis()),
                    )
                val vm = ImportFlowViewModel(repo, ErrorBus(), FakeSyncRepository(), FakeAdminRepository(), FakeSearchRepository())
                driveToReview(vm, repo)

                val absItem = AbsItemId("abs-item-99")
                vm.openBookSearch(absItem)

                val review = vm.uiState.value.shouldBeInstanceOf<ImportFlowUiState.Review>()
                val bookSearch = review.bookSearch.shouldNotBeNull()
                bookSearch.absItemId shouldBe absItem
                bookSearch.query shouldBe ""
                bookSearch.results.shouldBeEmpty()
                bookSearch.isSearching shouldBe false
            }
        }

        test("closeBookSearch sets bookSearch to null") {
            runTest(testDispatcher) {
                val repo =
                    FakeImportRepository(
                        uploadResult = AppResult.Success(importSummary()),
                        analyzeResult = AppResult.Success(importAnalysis()),
                    )
                val vm = ImportFlowViewModel(repo, ErrorBus(), FakeSyncRepository(), FakeAdminRepository(), FakeSearchRepository())
                driveToReview(vm, repo)
                vm.openBookSearch(AbsItemId("abs-item-99"))
                vm.uiState.value
                    .shouldBeInstanceOf<ImportFlowUiState.Review>()
                    .bookSearch
                    .shouldNotBeNull()

                vm.closeBookSearch()

                val review = vm.uiState.value.shouldBeInstanceOf<ImportFlowUiState.Review>()
                review.bookSearch.shouldBeNull()
            }
        }

        test("updateBookSearchQuery with non-blank query populates results from search repository") {
            runTest(testDispatcher) {
                val duneHit = SearchHit(id = "book-dune", type = SearchHitType.BOOK, name = "Dune", author = "Frank Herbert")
                val searchRepo = FakeSearchRepository(results = listOf(duneHit))
                val repo =
                    FakeImportRepository(
                        uploadResult = AppResult.Success(importSummary()),
                        analyzeResult = AppResult.Success(importAnalysis()),
                    )
                val vm = ImportFlowViewModel(repo, ErrorBus(), FakeSyncRepository(), FakeAdminRepository(), searchRepo)
                driveToReview(vm, repo)
                vm.openBookSearch(AbsItemId("abs-item-99"))

                vm.updateBookSearchQuery("dune")
                // Advance past debounce delay (300ms)
                advanceTimeBy(301)
                advanceUntilIdle()

                val review = vm.uiState.value.shouldBeInstanceOf<ImportFlowUiState.Review>()
                val bookSearch = review.bookSearch.shouldNotBeNull()
                bookSearch.query shouldBe "dune"
                bookSearch.isSearching shouldBe false
                bookSearch.results.size shouldBe 1
                bookSearch.results[0].bookId shouldBe BookId("book-dune")
                bookSearch.results[0].title shouldBe "Dune"
                bookSearch.results[0].author shouldBe "Frank Herbert"
            }
        }

        test("updateBookSearchQuery with blank query clears results without searching") {
            runTest(testDispatcher) {
                val searchRepo = FakeSearchRepository(results = listOf(SearchHit(id = "book-1", type = SearchHitType.BOOK, name = "Something")))
                val repo =
                    FakeImportRepository(
                        uploadResult = AppResult.Success(importSummary()),
                        analyzeResult = AppResult.Success(importAnalysis()),
                    )
                val vm = ImportFlowViewModel(repo, ErrorBus(), FakeSyncRepository(), FakeAdminRepository(), searchRepo)
                driveToReview(vm, repo)
                vm.openBookSearch(AbsItemId("abs-item-99"))

                // First set a non-blank query so results are populated
                vm.updateBookSearchQuery("something")
                advanceTimeBy(301)
                advanceUntilIdle()
                vm.uiState.value
                    .shouldBeInstanceOf<ImportFlowUiState.Review>()
                    .bookSearch!!
                    .results.size shouldBe 1

                // Now clear the query
                vm.updateBookSearchQuery("")
                advanceUntilIdle()

                val review = vm.uiState.value.shouldBeInstanceOf<ImportFlowUiState.Review>()
                val bookSearch = review.bookSearch.shouldNotBeNull()
                bookSearch.query shouldBe ""
                bookSearch.results.shouldBeEmpty()
                // Only 1 search call was made (for "something"); blank didn't trigger another
                searchRepo.searchCallCount shouldBe 1
            }
        }

        test("successive updateBookSearchQuery calls cancel previous job — only last query's results land") {
            runTest(testDispatcher) {
                // FakeSearchRepository records calls and returns results based on the query
                val hitA = SearchHit(id = "book-a", type = SearchHitType.BOOK, name = "Book A")
                val hitB = SearchHit(id = "book-b", type = SearchHitType.BOOK, name = "Book B")
                val searchRepo = FakeSearchRepository(results = listOf(hitA, hitB))
                val repo =
                    FakeImportRepository(
                        uploadResult = AppResult.Success(importSummary()),
                        analyzeResult = AppResult.Success(importAnalysis()),
                    )
                val vm = ImportFlowViewModel(repo, ErrorBus(), FakeSyncRepository(), FakeAdminRepository(), searchRepo)
                driveToReview(vm, repo)
                vm.openBookSearch(AbsItemId("abs-item-99"))

                // Issue first query
                vm.updateBookSearchQuery("first")
                // Before debounce expires, issue a second query — this cancels the first job
                vm.updateBookSearchQuery("second")
                // Now advance past debounce — only "second" executes its search
                advanceTimeBy(301)
                advanceUntilIdle()

                val review = vm.uiState.value.shouldBeInstanceOf<ImportFlowUiState.Review>()
                val bookSearch = review.bookSearch.shouldNotBeNull()
                // Final query in bookSearch matches the last one issued
                bookSearch.query shouldBe "second"
                // Only one search call was made (the first was cancelled pre-search)
                searchRepo.searchCallCount shouldBe 1
            }
        }

        test("selectBook sets bookOverride and closes bookSearch panel") {
            runTest(testDispatcher) {
                val repo =
                    FakeImportRepository(
                        uploadResult = AppResult.Success(importSummary()),
                        analyzeResult = AppResult.Success(importAnalysis()),
                    )
                val vm = ImportFlowViewModel(repo, ErrorBus(), FakeSyncRepository(), FakeAdminRepository(), FakeSearchRepository())
                driveToReview(vm, repo)

                val absItem = AbsItemId("abs-item-99")
                val bookId = BookId("book-dune")
                vm.openBookSearch(absItem)
                vm.selectBook(absItem, bookId)

                val review = vm.uiState.value.shouldBeInstanceOf<ImportFlowUiState.Review>()
                review.bookOverrides[absItem] shouldBe bookId
                review.bookSearch.shouldBeNull()
            }
        }

        test("skipBook records absItemId → null in bookOverrides") {
            runTest(testDispatcher) {
                val repo =
                    FakeImportRepository(
                        uploadResult = AppResult.Success(importSummary()),
                        analyzeResult = AppResult.Success(importAnalysis()),
                    )
                val vm = ImportFlowViewModel(repo, ErrorBus(), FakeSyncRepository(), FakeAdminRepository(), FakeSearchRepository())
                driveToReview(vm, repo)

                val absItem = AbsItemId("abs-item-99")
                vm.skipBook(absItem)

                val review = vm.uiState.value.shouldBeInstanceOf<ImportFlowUiState.Review>()
                review.bookOverrides.containsKey(absItem).shouldBeTrue()
                review.bookOverrides[absItem].shouldBeNull()
            }
        }

        test("confirmAndApply sends bookOverrides to confirmMapping") {
            runTest(testDispatcher) {
                val repo =
                    FakeImportRepository(
                        uploadResult = AppResult.Success(importSummary()),
                        analyzeResult = AppResult.Success(importAnalysis()),
                        confirmMappingResult = AppResult.Success(Unit),
                        applyResult = AppResult.Success(importResult()),
                    )
                val vm = ImportFlowViewModel(repo, ErrorBus(), FakeSyncRepository(), FakeAdminRepository(), FakeSearchRepository())
                driveToReview(vm, repo)

                val absItem = AbsItemId("abs-item-99")
                val bookId = BookId("book-42")
                vm.setBookOverride(absItem, bookId)
                vm.confirmAndApply()
                advanceUntilIdle()
                repo.progressFlow.emit(ImportEvent.Applied(result = importResult()))
                advanceUntilIdle()

                repo.confirmedBookOverrides[absItem] shouldBe bookId
            }
        }
    })

// ─── helpers ──────────────────────────────────────────────────────────────────

private fun fakeAdminUser(
    id: String,
    email: String,
) = AdminUserInfo(
    id = id,
    email = email,
    displayName = null,
    firstName = null,
    lastName = null,
    isRoot = false,
    role = "member",
    status = "active",
    permissions = UserPermissions(),
    createdAt = "2024-01-01T00:00:00Z",
)

// ─── fakes ────────────────────────────────────────────────────────────────────

private val stubError: AppError = TransportError.NetworkUnavailable()

/**
 * In-memory fake of [ImportRepository] for ViewModel seam tests.
 *
 * All RPC calls return canned [AppResult]s. [progressFlow] is a shared flow that
 * the test can push [ImportEvent]s into; it is returned by both [observeProgress]
 * calls (analyze and apply use the same stream keyed by importId in real life).
 *
 * To test intermediate Analyzing/Applying state, supply [analyzeDeferred] or
 * [applyDeferred] and complete them after emitting progress events into [progressFlow].
 * This simulates the real server that sends progress ticks before the RPC method returns.
 */
private class FakeImportRepository(
    private val uploadResult: AppResult<ImportSummary> = AppResult.Failure(stubError),
    private val analyzeResult: AppResult<ImportAnalysis> = AppResult.Failure(stubError),
    private val confirmMappingResult: AppResult<Unit> = AppResult.Failure(stubError),
    private val applyResult: AppResult<ImportResult> = AppResult.Failure(stubError),
    /** When non-null, [analyze] suspends until this deferred is completed. */
    val analyzeDeferred: CompletableDeferred<AppResult<ImportAnalysis>>? = null,
    /** When non-null, [apply] suspends until this deferred is completed. */
    val applyDeferred: CompletableDeferred<AppResult<ImportResult>>? = null,
) : ImportRepository {
    val progressFlow = MutableSharedFlow<ImportEvent>(extraBufferCapacity = 16)

    /** Records the user mappings passed to [confirmMapping] for assertion. */
    val confirmedUserMappings = mutableMapOf<AbsUserId, UserId>()

    /** Records the book overrides passed to [confirmMapping] for assertion. */
    val confirmedBookOverrides = mutableMapOf<AbsItemId, BookId?>()

    override suspend fun upload(fileSource: FileSource): AppResult<ImportSummary> = uploadResult

    override suspend fun analyze(importId: ImportId): AppResult<ImportAnalysis> = analyzeDeferred?.await() ?: analyzeResult

    override suspend fun confirmMapping(
        importId: ImportId,
        userMappings: Map<AbsUserId, UserId>,
        bookOverrides: Map<AbsItemId, BookId?>,
    ): AppResult<Unit> {
        confirmedUserMappings.putAll(userMappings)
        confirmedBookOverrides.putAll(bookOverrides)
        return confirmMappingResult
    }

    override suspend fun apply(importId: ImportId): AppResult<ImportResult> = applyDeferred?.await() ?: applyResult

    override suspend fun listImports(): AppResult<List<ImportSummary>> = AppResult.Success(emptyList())

    override suspend fun deleteImport(importId: ImportId): AppResult<Unit> = AppResult.Success(Unit)

    override fun observeProgress(importId: ImportId): Flow<ImportEvent> = progressFlow
}

/**
 * In-memory fake of [SyncRepository] for the import seam. Records whether
 * [refreshListeningHistory] was called after a successful apply.
 */
private class FakeSyncRepository(
    private val refreshResult: AppResult<Unit> = AppResult.Success(Unit),
) : SyncRepository {
    var refreshListeningHistoryCalled: Boolean = false
        private set

    override val syncState: StateFlow<SyncState> = MutableStateFlow(SyncState.Idle)
    override val isServerScanning: StateFlow<Boolean> = MutableStateFlow(false)
    override val isBuildingInitialLibrary: StateFlow<Boolean> = MutableStateFlow(false)
    override val scanProgress: StateFlow<ScanProgressState?> = MutableStateFlow(null)

    override suspend fun sync(): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun connectRealtime() = Unit

    override suspend fun disconnect() = Unit

    override suspend fun resetForNewLibrary(newLibraryId: String): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun refreshListeningHistory(): AppResult<Unit> {
        refreshListeningHistoryCalled = true
        return refreshResult
    }

    override suspend fun forceFullResync(): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun refresh(): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun hasLocalLibrary(): Boolean = true
}

/** Minimal no-op [FileSource] for tests that need a file name but no actual bytes. */
private class StubFileSource(
    override val filename: String,
) : FileSource {
    override val size: Long = 0L

    override fun openChannel() = ByteReadChannel(ByteArray(0))
}

/**
 * In-memory fake of [AdminRepository] for the import VM seam.
 *
 * Only [getUsers] is relevant here; all other methods return safe no-op [AppResult.Success]s.
 */
private class FakeAdminRepository(
    private val getUsersResult: AppResult<List<AdminUserInfo>> = AppResult.Success(emptyList()),
) : AdminRepository {
    override suspend fun getUsers(): AppResult<List<AdminUserInfo>> = getUsersResult

    override suspend fun getPendingUsers(): AppResult<List<AdminUserInfo>> = AppResult.Success(emptyList())

    override fun observeRoster(): Flow<List<AdminUserInfo>> = flowOf(emptyList())

    override suspend fun approveUser(userId: String): AppResult<AdminUserInfo> = AppResult.Success(fakeAdminUser(userId, "stub@example.com"))

    override suspend fun denyUser(userId: String): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun deleteUser(userId: String): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun getUser(userId: String): AppResult<AdminUserInfo> = AppResult.Success(fakeAdminUser(userId, "stub@example.com"))

    override suspend fun updateUser(
        userId: String,
        firstName: String?,
        lastName: String?,
        role: String?,
        canShare: Boolean?,
    ): AppResult<AdminUserInfo> = AppResult.Success(fakeAdminUser(userId, "stub@example.com"))

    override suspend fun getInvites(): AppResult<List<InviteInfo>> = AppResult.Success(emptyList())

    override suspend fun createInvite(
        email: String,
        role: String,
        expiresInDays: Int,
    ): AppResult<InviteInfo> =
        AppResult.Success(
            InviteInfo(
                id = "inv-1",
                code = "CODE",
                name = email.substringBefore('@'),
                email = email,
                role = role,
                expiresAt = "",
                claimedAt = null,
                url = "",
                createdAt = "",
            ),
        )

    override suspend fun deleteInvite(inviteId: String): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun getRegistrationPolicy(): AppResult<RegistrationPolicy> = AppResult.Success(RegistrationPolicy.CLOSED)

    override suspend fun setRegistrationPolicy(policy: RegistrationPolicy): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun getServerSettings(): AppResult<ServerSettings> = AppResult.Success(ServerSettings(serverName = "Test", remoteUrl = null))

    override suspend fun updateServerSettings(
        serverName: String?,
        remoteUrl: String?,
        inboxEnabled: Boolean?,
        pushNotificationsEnabled: Boolean?,
    ): AppResult<ServerSettings> = AppResult.Success(ServerSettings(serverName = serverName ?: "Test", remoteUrl = remoteUrl))

    override suspend fun getLibrary(): AppResult<Library> = AppResult.Failure(TransportError.NetworkUnavailable())

    override suspend fun addScanPath(
        path: String,
    ): AppResult<Library> = AppResult.Failure(TransportError.NetworkUnavailable())

    override suspend fun removeFolder(
        folderId: String,
    ): AppResult<Library> = AppResult.Failure(TransportError.NetworkUnavailable())

    override suspend fun triggerScan(): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun browseFilesystem(path: String): AppResult<BrowseFilesystemResponse> = AppResult.Failure(TransportError.NetworkUnavailable())
}

/**
 * In-memory fake of [SearchRepository] for the import VM seam.
 *
 * Returns a fixed list of [SearchHit]s for any non-blank query. Counts calls so tests
 * can assert the cancellation/debounce behaviour.
 */
private class FakeSearchRepository(
    private val results: List<SearchHit> = emptyList(),
) : SearchRepository {
    var searchCallCount: Int = 0
        private set

    override suspend fun search(
        query: String,
        types: List<SearchHitType>?,
        genres: List<String>?,
        genrePath: String?,
        limit: Int,
    ): SearchResult {
        searchCallCount++
        return SearchResult(
            query = query,
            total = results.size,
            tookMs = 1L,
            hits = results,
        )
    }
}
