package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.ScannerService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.server.scanner.ScanCoordinator
import com.calypsan.listenup.server.scanner.Scanner
import com.calypsan.listenup.server.scanner.ScannerBundle
import com.calypsan.listenup.server.scanner.ScannerServiceImpl
import com.calypsan.listenup.server.scanner.ScanOrchestrator
import com.calypsan.listenup.server.scanner.asPort
import com.calypsan.listenup.server.scanner.metadata.AbsMetadataReader
import com.calypsan.listenup.server.scanner.metadata.MetadataPrecedence
import com.calypsan.listenup.server.scanner.sidecar.DescTxtParser
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
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Koin module for the scanner slice. Wires:
 *
 *  - The [ScanEvent] event bus (writable [MutableSharedFlow] for the
 *    Scanner; read-only [SharedFlow] view for the RPC service and SSE).
 *  - [AbsMetadataReader], [ScanOrchestrator], [ScannerServiceImpl].
 *  - [WatcherSupervisor] backed by [FolderWatcher] for real-time updates.
 *
 * The [applicationScope] parameter is the long-lived coroutine scope
 * (typically `Application.coroutineContext + SupervisorJob`) — owns the
 * coordinator's worker and the watcher's event loop. Cancelling the scope
 * cancels everything cleanly on app shutdown.
 *
 * The module is only installed when [libraryPath] is set; the caller
 * (Application.module()) decides whether to skip installation when no
 * library path is configured.
 *
 * @param metadataPrecedence the operator-configured textual-metadata
 *   precedence (resolved from `LISTENUP_METADATA_PRECEDENCE`), threaded into
 *   the [Scanner] so every [com.calypsan.listenup.server.scanner.pipeline.Analyzer]
 *   it builds honours the configured order.
 */
fun scannerModule(
    libraryPath: Path,
    applicationScope: CoroutineScope,
    metadataPrecedence: MetadataPrecedence = MetadataPrecedence.DEFAULT,
): Module =
    module {
        single { libraryPath }
        single { applicationScope }

        // Event bus: one MutableSharedFlow as the writable side, exposed both
        // as MutableSharedFlow (for the Scanner to emit) and as SharedFlow
        // (for ScannerServiceImpl to expose to clients without leaking emit).
        single<MutableSharedFlow<ScanEvent>> {
            MutableSharedFlow(replay = 0, extraBufferCapacity = 64)
        }
        single<SharedFlow<ScanEvent>> { get<MutableSharedFlow<ScanEvent>>().asSharedFlow() }

        // Scan-result bus: the BookPersister consumes the most recent ScanResult.
        // replay = 1 lets a late subscriber pick up the last scan; DROP_OLDEST
        // keeps a fast scan stream from ever blocking the Scanner. Qualified by
        // name because Koin keys on the erased KClass — an unqualified
        // MutableSharedFlow<ScanResult> would collide with the ScanEvent bus.
        single<MutableSharedFlow<ScanResult>>(named("scanResultBus")) {
            MutableSharedFlow(replay = 1, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        }

        single { AbsMetadataReader(contractJson) }

        single { StableSizeDebouncer() }

        // WatcherSupervisor — creates one FolderWatcher per registered folder.
        // The factory lambda starts the watcher, subscribes to its event flow,
        // and forwards events to the caller's onEvent callback.
        single<WatcherSupervisorPort> {
            val scope: CoroutineScope = get()
            val debouncer: StableSizeDebouncer = get()
            WatcherSupervisor { folder, onEvent ->
                val folderPath = Path.of(folder.rootPath)
                val watcher =
                    FolderWatcher(
                        libraryRoot = folderPath,
                        scope = scope,
                        debouncer = debouncer,
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
            }.asPort()
        }

        // ScanOrchestrator — one Scanner + ScanCoordinator bundle per library.
        // Currently the server runs with a single library (multi-library support
        // lands in Task 18). The factory lambda is called once per onLibraryAdded().
        single {
            val scope: CoroutineScope = get()
            val eventBus: MutableSharedFlow<ScanEvent> = get()
            val scanResultBus: MutableSharedFlow<ScanResult> = get(named("scanResultBus"))
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
                            metadataPrecedence = metadataPrecedence,
                        )
                    val coordinator =
                        ScanCoordinator(
                            libraryId = library.id,
                            runFullScan = { scanner.runFullScan() },
                            runIncremental = { scanner.runIncremental(it) },
                            scope = scope,
                        )
                    ScannerBundle(library, scanner, coordinator)
                },
                watcherSupervisor = get<WatcherSupervisorPort>(),
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
            )
        }
    }
