package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.ScannerService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.Library
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.server.scanner.ScanIssueRepository
import com.calypsan.listenup.server.scanner.reconcile
import com.calypsan.listenup.server.scanner.ScanIssueStore
import com.calypsan.listenup.server.scanner.ScanCoordinator
import com.calypsan.listenup.server.sidecar.SidecarWriter
import com.calypsan.listenup.server.scanner.Scanner
import com.calypsan.listenup.server.scanner.ScannerBundle
import com.calypsan.listenup.server.scanner.ScannerServiceImpl
import com.calypsan.listenup.server.scanner.ScanOrchestrator
import com.calypsan.listenup.server.scanner.metadata.AbsMetadataReader
import com.calypsan.listenup.server.librarywrite.SelfWriteRegistry
import com.calypsan.listenup.server.scanner.metadata.MetadataPrecedence
import com.calypsan.listenup.server.scanner.metadata.resolveLibraryPrecedence
import com.calypsan.listenup.server.scanner.sidecar.DescTxtParser
import com.calypsan.listenup.server.scanner.sidecar.ListenUpSidecarReader
import com.calypsan.listenup.server.scanner.sidecar.NfoParser
import com.calypsan.listenup.server.scanner.sidecar.OpfParser
import com.calypsan.listenup.server.scanner.sidecar.ReaderTxtParser
import com.calypsan.listenup.server.scanner.watcher.FolderWatcher
import com.calypsan.listenup.server.scanner.watcher.StableSizeDebouncer
import com.calypsan.listenup.server.scanner.watcher.WatcherHandle
import com.calypsan.listenup.server.scanner.watcher.WatcherSupervisor
import com.calypsan.listenup.server.scanner.WatcherSupervisorPort
import com.calypsan.listenup.server.services.LibraryRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import org.koin.core.module.Module
import org.koin.dsl.module

private val logger = KotlinLogging.logger("com.calypsan.listenup.server.di.ScannerModule")

/**
 * Koin module for the scanner slice. Wires:
 *
 *  - The [ScanEvent] event bus (writable [MutableSharedFlow] for the
 *    Scanner; read-only [SharedFlow] view for the RPC progress service).
 *  - [AbsMetadataReader], [ScanOrchestrator], [ScannerServiceImpl].
 *  - [WatcherSupervisor] backed by [FolderWatcher] for real-time updates.
 *
 * The [applicationScope] parameter is the long-lived coroutine scope
 * (typically `Application.coroutineContext + SupervisorJob`) — owns the
 * coordinator's worker and the watcher's event loop. Cancelling the scope
 * cancels everything cleanly on app shutdown.
 *
 * Libraries are mounted at runtime from the database via
 * [ScanOrchestrator.onLibraryAdded] — the module carries no boot-time
 * library path. The caller (Application.module()) still decides whether to
 * install the module at all.
 *
 * @param metadataPrecedence the operator-configured textual-metadata
 *   precedence (resolved from `LISTENUP_METADATA_PRECEDENCE`), threaded into
 *   the [Scanner] so every [com.calypsan.listenup.server.scanner.pipeline.Analyzer]
 *   it builds honours the configured order.
 * @param watchEnabled when `false`, the [ScanOrchestrator] skips mounting
 *   real-time file-system watchers (resolved from `scanner.watchEnabled`,
 *   default `true`). Tests disable it to stop the watcher racing fixture writes.
 */
fun scannerModule(
    applicationScope: CoroutineScope,
    metadataPrecedence: MetadataPrecedence = MetadataPrecedence.DEFAULT,
    watchEnabled: Boolean = true,
): Module =
    module {
        single { applicationScope }

        // Event bus: one MutableSharedFlow as the writable side, exposed both
        // as MutableSharedFlow (for the Scanner to emit) and as SharedFlow
        // (for ScannerServiceImpl to expose to clients without leaking emit).
        // Qualified by name because Koin keys on the erased KClass — an unqualified
        // MutableSharedFlow<ScanEvent> would collide with the backup/import event buses.
        single<MutableSharedFlow<ScanEvent>>(EventBusQualifiers.ScanEvents) {
            MutableSharedFlow(replay = 0, extraBufferCapacity = 64)
        }
        single<SharedFlow<ScanEvent>> {
            get<MutableSharedFlow<ScanEvent>>(
                EventBusQualifiers.ScanEvents,
            ).asSharedFlow()
        }

        // Scan-result bus: the BookPersister consumes each ScanResult as it arrives.
        // replay = 0: BookPersister subscribes before any scan starts, so no replay is needed — and
        // replaying the last artwork-bearing result would re-pin ~230MB of embedded cover bytes in the
        // SharedFlow's internal buffer between scans.
        //
        // onBufferOverflow = SUSPEND is load-bearing (finding A4): this seam must be NON-LOSSY. The
        // persister does heavy per-result DB + cover I/O, so a bulk reorg can queue scan results faster
        // than it drains. The prior DROP_OLDEST silently EVICTED the oldest outstanding result once more
        // than the buffer held — its Added/Modified never persisted, its Removed tombstones never applied,
        // its Completed never fired. SUSPEND back-pressures the scanner instead (the scanner waits for the
        // persister, which is correct), so no scan result is ever dropped. Qualified by name because Koin
        // keys on the erased KClass — an unqualified MutableSharedFlow<ScanResult> would collide with the
        // ScanEvent bus.
        single<MutableSharedFlow<ScanResult>>(EventBusQualifiers.ScanResults) {
            MutableSharedFlow(replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.SUSPEND)
        }

        single { AbsMetadataReader(contractJson) }

        single { StableSizeDebouncer() }

        // WatcherSupervisor — creates one FolderWatcher per registered folder.
        // The factory lambda starts the watcher, subscribes to its event flow,
        // and forwards events to the caller's onEvent callback.
        single<WatcherSupervisorPort> {
            val scope: CoroutineScope = get()
            val debouncer: StableSizeDebouncer = get()
            val selfWrites: SelfWriteRegistry = get()
            WatcherSupervisor { folder, onEvent ->
                // Scanner-internal refs are always system-built with a real path; a null here
                // means a member-redacted projection leaked into the scanner — fail loudly.
                val folderPath =
                    Path(
                        requireNotNull(folder.rootPath) {
                            "library folder ${folder.id.value} reached the watcher without a root path"
                        },
                    )
                val watcher =
                    FolderWatcher(
                        libraryRoot = folderPath,
                        scope = scope,
                        debouncer = debouncer,
                        selfWrites = selfWrites,
                    )
                val job: Job =
                    scope.launch {
                        try {
                            watcher.start()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            val msg =
                                "FolderWatcher failed to start for ${folder.rootPath}: " +
                                    "${e.message} — watcher disabled for this folder"
                            logger.warn(msg)
                            return@launch
                        }
                        watcher.events.collect { path -> onEvent(path) }
                    }
                object : WatcherHandle {
                    override suspend fun close() {
                        job.cancel()
                        watcher.close()
                    }
                }
            }
        }

        // ScanOrchestrator — the Scanner + ScanCoordinator bundle for the library.
        // The factory lambda is called once, when the library is registered (onLibraryAdded).
        single {
            val scope: CoroutineScope = get()
            val eventBus: MutableSharedFlow<ScanEvent> = get(EventBusQualifiers.ScanEvents)
            val scanResultBus: MutableSharedFlow<ScanResult> = get(EventBusQualifiers.ScanResults)
            val metadataReader: AbsMetadataReader = get()
            val embeddedMetadataParser: com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser = get()
            ScanOrchestrator(
                scannerFactory = { library ->
                    val scanner =
                        Scanner(
                            library = library,
                            metadataReader = metadataReader,
                            embeddedMetadataParser = embeddedMetadataParser,
                            eventBus = eventBus,
                            scanResultBus = scanResultBus,
                            sidecarParsers = listOf(NfoParser(), OpfParser(), ReaderTxtParser(), DescTxtParser()),
                            metadataPrecedence =
                                resolveLibraryPrecedence(
                                    library.metadataPrecedence,
                                    metadataPrecedence,
                                ),
                            coverSpool = get(),
                            listenUpSidecarReader = ListenUpSidecarReader(get()),
                        )
                    val coordinator =
                        ScanCoordinator(
                            libraryId = library.id,
                            runFullScan = {
                                scanner.runFullScan().also { result ->
                                    // Sidecars are a property of the library, not of having edited
                                    // something — so a completed scan reconciles them. Nullable
                                    // because the sidecar module isn't loaded in minimal containers.
                                    getOrNull<SidecarWriter>()?.backfillStaleSidecars()
                                    // Same reasoning for the issue record: what the scanner could
                                    // not import is a property of the library as it stands now, so
                                    // the scan that just looked is what should say so.
                                    getOrNull<ScanIssueRepository>().reconcileWith(library, result)
                                }
                            },
                            runIncremental = { path ->
                                // The commonest repair path: the user fixes a folder on disk and
                                // the watcher re-analyses just that subtree. Reconciling here is
                                // what stops a fixed book sitting in the inbox forever. Null means
                                // the pass was superseded — its result is stale, so it says nothing
                                // about the current state and must not clear anything.
                                scanner.runIncremental(path)?.let { result ->
                                    getOrNull<ScanIssueRepository>().reconcileWith(library, result)
                                }
                                Unit
                            },
                            scope = scope,
                        )
                    ScannerBundle(library, scanner, coordinator)
                },
                watcherSupervisor = get<WatcherSupervisorPort>(),
                watchEnabled = watchEnabled,
            )
        }

        single<ScannerService> {
            val libraryRegistry: LibraryRegistry = get()
            ScannerServiceImpl(
                orchestrator = get(),
                // LibraryRegistry caches the id after the first DB look-up; calling
                // it per-request is safe and keeps the binding synchronous.
                resolveLibraryId = { libraryRegistry.currentLibrary() },
                eventBus = get<SharedFlow<ScanEvent>>(),
                scanIssues = get(),
            )
        }

        // The durable record of what the scanner could NOT import — see ScanIssueRepository.
        //
        // Registered under BOTH types on purpose. The scan reconcile resolves the concrete
        // repository (it needs `record`/`clear`, which are not on the port), while ScannerService
        // takes the narrow read port. Binding only the port left the reconcile's `getOrNull` lookup
        // returning null, so it silently did nothing — no error, no issues ever recorded.
        single { ScanIssueRepository(db = get()) }
        single<ScanIssueStore> { get<ScanIssueRepository>() }
    }

/**
 * Brings the scan-issue record in line with a completed scan.
 *
 * Null receiver means the container has no issue record bound — the minimal test containers — and
 * reconciling is then a no-op. Extracted from the two `runFullScan` / `runIncremental` lambdas so
 * `scannerModule` reads as wiring rather than as logic, and so the two paths cannot drift apart.
 */
private suspend fun ScanIssueRepository?.reconcileWith(
    library: Library,
    result: ScanResult,
) {
    this?.reconcile(
        libraryId = library.id,
        folderRoots = library.folders.mapNotNull { it.rootPath },
        importedRelPaths = result.books.map { it.candidate.rootRelPath },
        errors = result.errors,
    )
}
