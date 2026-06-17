package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.ImportService
import com.calypsan.listenup.api.dto.imports.ImportEvent
import com.calypsan.listenup.server.absimport.AbsBackupReader
import com.calypsan.listenup.server.absimport.BookMatcher
import com.calypsan.listenup.server.absimport.ImportAnalyzer
import com.calypsan.listenup.server.absimport.ImportApplier
import com.calypsan.listenup.server.absimport.ImportPaths
import com.calypsan.listenup.server.absimport.ImportStore
import com.calypsan.listenup.server.absimport.MappingValidator
import com.calypsan.listenup.server.absimport.SessionConverter
import com.calypsan.listenup.server.absimport.UserMatcher
import com.calypsan.listenup.server.api.ImportServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import kotlinx.coroutines.flow.MutableSharedFlow
import org.koin.core.module.Module
import org.koin.dsl.module
import java.nio.file.Path

/**
 * Koin module for the Audiobookshelf-import slice.
 *
 * Provides the full import pipeline:
 *  - [ImportPaths] — working directories under `$LISTENUP_HOME/imports/`.
 *  - [ImportStore] — list/status/delete + analysis/matches/mapping JSON read/write.
 *  - [AbsBackupReader] — read-only reads of a staged `absdatabase.sqlite`.
 *  - [BookMatcher] / [UserMatcher] — confidence-tiered matching against the live library.
 *  - [MappingValidator] — rejects an incoherent admin mapping before it is written.
 *  - [ImportAnalyzer] — read + match → [com.calypsan.listenup.api.dto.imports.ImportAnalysis].
 *  - [com.calypsan.listenup.server.absimport.SessionConverter] — ABS session → listening event.
 *  - [ImportApplier] — write listening progress + sessions, then backfill per-user stats.
 *  - [MutableSharedFlow]<[ImportEvent]> — process-wide progress event bus.
 *  - [ImportService] / [ImportServiceImpl] — admin-only RPC surface.
 *
 * [org.jetbrains.exposed.v1.jdbc.Database], [com.calypsan.listenup.server.services.LibraryRegistry],
 * [com.calypsan.listenup.server.services.PlaybackPositionRepository],
 * [com.calypsan.listenup.server.services.ListeningEventRepository], and
 * [com.calypsan.listenup.server.services.UserStatsBackfillService] are resolved from the auth, books,
 * and playback modules respectively (all installed in the same Koin container).
 */
fun importModule(homeDir: Path): Module =
    module {
        single { ImportPaths(homeDir) }
        single { ImportStore(get()) }
        single { AbsBackupReader() }
        single { BookMatcher(get()) }
        single { UserMatcher() }
        single { MappingValidator(get()) }
        single { SessionConverter() }

        single {
            ImportAnalyzer(
                reader = get(),
                store = get(),
                paths = get(),
                bookMatcher = get(),
                userMatcher = get(),
                libraryRegistry = get(),
                db = get(),
            )
        }

        single {
            ImportApplier(
                reader = get(),
                store = get(),
                paths = get(),
                playbackPositionRepository = get(),
                sessionConverter = get(),
                listeningEventRepository = get(),
                statsBackfill = get(),
            )
        }

        single<MutableSharedFlow<ImportEvent>> {
            MutableSharedFlow(replay = 0, extraBufferCapacity = 64)
        }

        single<ImportService> {
            ImportServiceImpl(
                store = get(),
                analyzer = get(),
                applier = get(),
                validator = get(),
                eventBus = get(),
                principal = unscopedImportPlaceholder("ImportService"),
            )
        }
    }

private fun unscopedImportPlaceholder(serviceName: String): PrincipalProvider =
    PrincipalProvider { error("Unscoped $serviceName — call copyWith(PrincipalProvider) at the route") }
