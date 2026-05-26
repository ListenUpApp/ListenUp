package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.ContributorService
import com.calypsan.listenup.api.SearchService
import com.calypsan.listenup.api.SeriesService
import com.calypsan.listenup.api.TagService
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.server.api.BookServiceImpl
import com.calypsan.listenup.server.api.ContributorServiceImpl
import com.calypsan.listenup.server.api.SearchServiceImpl
import com.calypsan.listenup.server.api.SeriesServiceImpl
import com.calypsan.listenup.server.api.TagServiceImpl
import com.calypsan.listenup.server.sync.BookSearchReindexer
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.TagRepository
import com.calypsan.listenup.server.cover.CoverResponder
import com.calypsan.listenup.server.cover.EmbeddedCoverCache
import com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser
import com.calypsan.listenup.server.scanner.metadata.MetadataPrecedence
import com.calypsan.listenup.server.services.BookIngestPort
import com.calypsan.listenup.server.services.BookPersister
import com.calypsan.listenup.server.services.BookPersisterMetrics
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.services.SeriesRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.flow.MutableSharedFlow
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.nio.file.Path

/**
 * Koin module for the books slice. Wires:
 *
 *  - [MeterRegistry] — a [SimpleMeterRegistry] backing [BookPersisterMetrics].
 *    There's no Prometheus scrape today; the counter is a countable diagnostic
 *    signal in logs, not a metrics pipeline (project "no premature
 *    observability" stance). [BookPersisterMetrics] is its only consumer.
 *  - [LibraryRegistry] — single-library bootstrap keyed off `LISTENUP_LIBRARY_PATH`.
 *  - [ContributorRepository] / [SeriesRepository] — the contributors and series
 *    syncable domains. `createdAtStart = true` so each registers with
 *    `SyncRegistry` at bootstrap, listing `"contributors"` / `"series"` on
 *    `/api/v1/sync/domains`. [BookRepository] depends on both to resolve the
 *    aggregate's contributor/series ids before its junction-row writes.
 *  - [BookRepository] — the books aggregate's [SyncableRepository][com.calypsan.listenup.server.sync.SyncableRepository].
 *    `createdAtStart = true` so its `init` block registers with `SyncRegistry`
 *    at bootstrap, making `/api/v1/sync/domains` list `"books"` on the first request.
 *  - [BookIngestPort] — bound to the same [BookRepository] instance.
 *  - [BookPersister] — consumes the scanner's [ScanResult] stream.
 *  - [EmbeddedCoverCache] — LRU cache for extracted embedded artwork.
 *  - [CoverResponder] — serves cover bytes for `GET /api/v1/books/{id}/cover`;
 *    pulls [EmbeddedMetadataParser] from the separately-installed
 *    `embeddedmetaModule`.
 *
 * Exposed as a **function** rather than a top-level `val` for the same reason
 * as [syncModule] — each Koin container receives a fresh [Module] (and a fresh
 * `SingleInstanceFactory` per binding), so instances never leak across containers.
 *
 * Installed only alongside [scannerModule] (i.e. when a library path is
 * configured): [BookPersister] depends on the scanner's `scanResultBus` and
 * the application [CoroutineScope][kotlinx.coroutines.CoroutineScope], both of
 * which only exist when the scanner slice is wired. With no library configured
 * there is no books domain — `/api/v1/sync/domains` correctly omits `"books"`.
 *
 * @param libraryPath the resolved library root — the same [Path] passed to
 *   [scannerModule]. [LibraryRegistry] keys the single `libraries` row off it.
 *   Passing the resolved path (rather than re-reading `System.getenv()`) keeps
 *   the books slice consistent with the scanner slice: both are driven by the
 *   one path `Application.module()` already resolved from configuration, so a
 *   config override of `scanner.libraryPath` reaches both.
 * @param metadataPrecedence the operator-configured textual-metadata precedence
 *   (resolved from `LISTENUP_METADATA_PRECEDENCE`). [LibraryRegistry] persists it
 *   onto the `libraries` row at bootstrap.
 * @param embeddedCoverCacheSize the operator-configured maximum number of covers
 *   the [EmbeddedCoverCache] retains (resolved from
 *   `LISTENUP_EMBEDDED_COVER_CACHE_SIZE`).
 */
fun booksModule(
    libraryPath: Path,
    metadataPrecedence: MetadataPrecedence = MetadataPrecedence.DEFAULT,
    embeddedCoverCacheSize: Int = DEFAULT_EMBEDDED_COVER_CACHE_SIZE,
): Module =
    module {
        single<MeterRegistry> { SimpleMeterRegistry() }
        single { BookPersisterMetrics(get()) }

        single {
            LibraryRegistry(
                db = get(),
                env = mapOf("LISTENUP_LIBRARY_PATH" to libraryPath.toString()),
                metadataPrecedence = metadataPrecedence,
            )
        }

        single(createdAtStart = true) { ContributorRepository(get(), get(), get()) }
        single(createdAtStart = true) { SeriesRepository(get(), get(), get()) }
        single(createdAtStart = true) {
            BookRepository(get(), get(), get(), get(), get(), get(), bookTagRepository = getOrNull())
        }
        single<BookIngestPort> { get<BookRepository>() }
        single<BookService> { BookServiceImpl(get<BookRepository>()) }
        single<ContributorService> { ContributorServiceImpl(contributorRepo = get(), bookRepo = get()) }
        single<SeriesService> { SeriesServiceImpl(seriesRepo = get(), bookRepo = get()) }
        single<SearchService> { SearchServiceImpl(db = get()) }
        single { BookSearchReindexer(get<BookTagRepository>(), get<TagRepository>(), get()) }
        single<TagService> {
            TagServiceImpl(
                get<TagRepository>(),
                get<BookTagRepository>(),
                get<BookSearchReindexer>(),
                get(),
            )
        }

        single { EmbeddedCoverCache(maxSize = embeddedCoverCacheSize) }
        single {
            CoverResponder(
                repository = get<BookRepository>(),
                cache = get(),
                parser = get<EmbeddedMetadataParser>(),
            )
        }

        single {
            BookPersister(
                ingest = get(),
                libraryRegistry = get(),
                db = get(),
                scanResultBus = get<MutableSharedFlow<ScanResult>>(named("scanResultBus")),
                scope = get(),
                metrics = get(),
            )
        }
    }

/**
 * Default [EmbeddedCoverCache] capacity used when `scanner.embeddedCoverCacheSize`
 * is unset. Matches the cache's own built-in default.
 */
private const val DEFAULT_EMBEDDED_COVER_CACHE_SIZE = 1000
