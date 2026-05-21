package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.ScannerService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.server.scanner.ScanCoordinator
import com.calypsan.listenup.server.scanner.Scanner
import com.calypsan.listenup.server.scanner.ScannerServiceImpl
import com.calypsan.listenup.server.scanner.metadata.AbsMetadataReader
import com.calypsan.listenup.server.scanner.metadata.MetadataPrecedence
import com.calypsan.listenup.server.scanner.sidecar.DescTxtParser
import com.calypsan.listenup.server.scanner.sidecar.NfoParser
import com.calypsan.listenup.server.scanner.sidecar.OpfParser
import com.calypsan.listenup.server.scanner.sidecar.ReaderTxtParser
import com.calypsan.listenup.server.scanner.watcher.FolderWatcher
import com.calypsan.listenup.server.scanner.watcher.StableSizeDebouncer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.nio.file.Path

/**
 * Koin module for the scanner slice. Wires:
 *
 *  - The [ScanEvent] event bus (writable [MutableSharedFlow] for the
 *    Scanner; read-only [SharedFlow] view for the RPC service and SSE).
 *  - [AbsMetadataReader], [Scanner], [ScanCoordinator], [ScannerServiceImpl].
 *  - [FolderWatcher] for real-time updates.
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

        single {
            Scanner(
                rootPath = get(),
                metadataReader = get(),
                embeddedMetadataParser = get(),
                eventBus = get<MutableSharedFlow<ScanEvent>>(),
                scanResultBus = get<MutableSharedFlow<ScanResult>>(named("scanResultBus")),
                sidecarParsers = listOf(NfoParser(), OpfParser(), ReaderTxtParser(), DescTxtParser()),
                metadataPrecedence = metadataPrecedence,
            )
        }

        single {
            val scanner: Scanner = get()
            ScanCoordinator(
                runFullScan = { scanner.runFullScan() },
                runIncremental = { scanner.runIncremental(it) },
                scope = get(),
            )
        }

        single<ScannerService> {
            ScannerServiceImpl(
                scanner = get(),
                coordinator = get(),
                eventBus = get<SharedFlow<ScanEvent>>(),
            )
        }

        single { StableSizeDebouncer() }

        single {
            FolderWatcher(
                libraryRoot = get(),
                scope = get(),
                debouncer = get(),
            )
        }
    }
