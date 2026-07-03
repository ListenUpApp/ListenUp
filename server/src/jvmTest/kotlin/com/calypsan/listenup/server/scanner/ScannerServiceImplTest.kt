@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.dto.scanner.ScanResultSummary
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.scanner.ChangeEventDto
import com.calypsan.listenup.api.dto.scanner.ScanPhase
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.ScanError
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.scanner.metadata.AbsMetadataReader
import com.calypsan.listenup.server.testing.testLibrary
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class ScannerServiceImplTest :
    FunSpec({

        test("scanFull with an ADMIN principal returns Success with a summary built from the latest result") {
            runTest {
                audioLibrary {
                    book("Author/Title") { tracks(count = 2) }
                }.use { fixture ->
                    val (service, _) = newService(fixture, scope = this)
                    val result = service.copyWith(principalOf(UserRole.ADMIN)).scanFull()
                    val success = result.shouldBeInstanceOf<AppResult.Success<ScanResultSummary>>()
                    success.data.totalBooks shouldBe 1
                    success.data.added shouldBe 1
                    success.data.errors shouldBe 0
                }
            }
        }

        test("scanFull with a MEMBER principal returns PermissionDenied") {
            runTest {
                audioLibrary {
                    book("Author/Title") { tracks(count = 2) }
                }.use { fixture ->
                    val (service, _) = newService(fixture, scope = this)
                    val result = service.copyWith(principalOf(UserRole.MEMBER)).scanFull()
                    result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }

        test("scanFull without a bound principal returns PermissionDenied") {
            runTest {
                audioLibrary {
                    book("Author/Title") { tracks(count = 2) }
                }.use { fixture ->
                    val (service, _) = newService(fixture, scope = this)
                    val result = service.scanFull()
                    result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }

        test("lastScanResult returns LibraryPathNotConfigured before any scan has run") {
            runTest {
                audioLibrary {}.use { fixture ->
                    val (service, _) = newService(fixture, scope = this)
                    val result = service.lastScanResult()
                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<ScanError.LibraryPathNotConfigured>()
                }
            }
        }

        test("observeProgress drops heavy Change events, keeping only lifecycle/progress") {
            runTest {
                // replay so the late subscriber sees all pre-emitted events deterministically.
                val eventBus = MutableSharedFlow<ScanEvent>(replay = 10)
                val orchestrator =
                    ScanOrchestrator(
                        scannerFactory = { error("scannerFactory unused by observeProgress") },
                        watcherSupervisor = NoOpWatcherSupervisor,
                    )
                val service = ScannerServiceImpl(orchestrator, { TEST_LIBRARY_ID }, eventBus.asSharedFlow())

                eventBus.emit(ScanEvent.Started(correlationId = "c", libraryId = TEST_LIBRARY_ID, rootPath = "/x"))
                // A Change carries a full AnalyzedBook (artwork bytes) — must NOT reach the progress stream.
                eventBus.emit(
                    ScanEvent.Change(
                        correlationId = "c",
                        libraryId = TEST_LIBRARY_ID,
                        event = ChangeEventDto.Removed(rootRelPath = "Author/Title"),
                    ),
                )
                eventBus.emit(
                    ScanEvent.Progress(
                        correlationId = "c",
                        libraryId = TEST_LIBRARY_ID,
                        phase = ScanPhase.ANALYZING,
                        filesWalked = 2,
                        booksAnalyzed = 1,
                        errors = 0,
                    ),
                )

                // Change is filtered, so the first two delivered events are Started then Progress.
                val received =
                    service
                        .observeProgress()
                        .take(2)
                        .toList()
                        .map { (it as RpcEvent.Data).value::class.simpleName }

                received shouldContainExactly listOf("Started", "Progress")
            }
        }

        test("lastScanResult returns Success after a scan completes") {
            runTest {
                audioLibrary {
                    book("Author/Title") { tracks(count = 1) }
                }.use { fixture ->
                    val (service, _) = newService(fixture, scope = this)
                    service.copyWith(principalOf(UserRole.ADMIN)).scanFull()

                    val result = service.lastScanResult()
                    val success = result.shouldBeInstanceOf<AppResult.Success<ScanResult>>()
                    success.data.books.size shouldBe 1
                }
            }
        }

        test("lastScanResult with a MEMBER principal is still allowed") {
            runTest {
                audioLibrary {
                    book("Author/Title") { tracks(count = 1) }
                }.use { fixture ->
                    val (service, _) = newService(fixture, scope = this)
                    service.copyWith(principalOf(UserRole.ADMIN)).scanFull()

                    val result = service.copyWith(principalOf(UserRole.MEMBER)).lastScanResult()
                    result.shouldBeInstanceOf<AppResult.Success<ScanResult>>()
                }
            }
        }
    })

private val TEST_LIBRARY_ID = LibraryId("test-lib")

private fun principalOf(role: UserRole) =
    PrincipalProvider { UserPrincipal(UserId("u-test"), SessionId("s-test"), role) }

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private suspend fun newService(
    fixture: AudioLibraryFixture,
    scope: TestScope,
): Pair<ScannerServiceImpl, ScanOrchestrator> {
    val eventBus = MutableSharedFlow<ScanEvent>(replay = 0, extraBufferCapacity = 64)
    val library = testLibrary(id = TEST_LIBRARY_ID.value, folders = listOf(fixture.root.toString()))
    val scanner =
        Scanner(
            library = library,
            metadataReader = AbsMetadataReader(contractJson),
            embeddedMetadataParser =
                com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser(
                    detector =
                        com.calypsan.listenup.server.embeddedmeta
                            .AudioFormatDetector(),
                    parsers = emptyList(),
                ),
            eventBus = eventBus,
            scanResultBus = MutableSharedFlow(replay = 1),
        )
    val coordinator =
        ScanCoordinator(
            libraryId = TEST_LIBRARY_ID,
            runFullScan = { scanner.runFullScan() },
            runIncremental = { scanner.runIncremental(it) },
            scope = scope.backgroundScope,
        )
    val bundle = ScannerBundle(library, scanner, coordinator)
    val orchestrator =
        ScanOrchestrator(
            scannerFactory = { bundle },
            watcherSupervisor = NoOpWatcherSupervisor,
        )
    orchestrator.onLibraryAdded(library)
    val service = ScannerServiceImpl(orchestrator, { TEST_LIBRARY_ID }, eventBus.asSharedFlow())
    return service to orchestrator
}

/** Watcher supervisor that does nothing — used when the test doesn't need FS events. */
private object NoOpWatcherSupervisor : WatcherSupervisorPort {
    override suspend fun mount(
        libraryId: com.calypsan.listenup.core.LibraryId,
        folder: com.calypsan.listenup.api.dto.LibraryFolderRef,
        onEvent: suspend (com.calypsan.listenup.core.LibraryId, kotlinx.io.files.Path) -> Unit,
    ) = Unit

    override suspend fun unmount(folderId: com.calypsan.listenup.core.FolderId) = Unit

    override suspend fun unmountAllForLibrary(libraryId: com.calypsan.listenup.core.LibraryId) = Unit

    override suspend fun unmountAll() = Unit
}
