package com.calypsan.listenup.server.di

import com.calypsan.listenup.server.scanner.ScanOrchestrator
import com.calypsan.listenup.server.scanner.sidecar.ListenUpSidecarReader
import com.calypsan.listenup.server.upload.UploadDuplicateDetector
import com.calypsan.listenup.server.upload.UploadFinalizer
import com.calypsan.listenup.server.upload.UploadIngestTrigger
import com.calypsan.listenup.server.upload.UploadPaths
import com.calypsan.listenup.server.upload.UploadStaging
import kotlinx.io.files.Path
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin module for the book-upload slice: staging under `$LISTENUP_HOME/uploads/`, duplicate
 * detection against the live library, and the finalize pipeline that moves a staged tree into a
 * library folder through the write broker.
 *
 * Depends on three slices it deliberately does not duplicate — [libraryWriteModule] for the
 * broker, [organizeModule] for the live path-derivation rules, and [scannerModule] for the
 * analyzer and the incremental-scan trigger that turns landed files into books.
 *
 * [homeDir] is the data-home directory that also holds the live database.
 */
fun uploadModule(homeDir: Path): Module =
    module {
        single { UploadPaths(homeDir) }
        single { UploadStaging(paths = get()) }
        single { UploadDuplicateDetector(sql = get()) }
        // The scanner's own suppression is why this exists: an upload writes through the broker,
        // so the watcher never sees it and something has to say "this one is new".
        single<UploadIngestTrigger> {
            val orchestrator: ScanOrchestrator = get()
            UploadIngestTrigger { bookRoot -> orchestrator.reanalyzeSubtree(bookRoot) }
        }
        single {
            UploadFinalizer(
                staging = get(),
                settingsStore = get(),
                duplicates = get(),
                broker = get(),
                libraryRegistry = get(),
                sql = get(),
                metadataReader = get(),
                embeddedMetadataParser = get(),
                listenUpSidecarReader = ListenUpSidecarReader(get()),
                ingest = get(),
            )
        }
    }
