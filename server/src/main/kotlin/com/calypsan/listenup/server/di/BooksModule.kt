package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.api.ContributorService
import com.calypsan.listenup.api.GenreService
import com.calypsan.listenup.server.seed.GenreDomainSeeder
import com.calypsan.listenup.server.seed.MoodDomainSeeder
import com.calypsan.listenup.api.MoodService
import com.calypsan.listenup.api.SearchService
import com.calypsan.listenup.api.SeriesService
import com.calypsan.listenup.api.TagService
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.api.BookServiceImpl
import com.calypsan.listenup.server.api.CollectionAccessPolicy
import com.calypsan.listenup.server.api.CollectionServiceImpl
import com.calypsan.listenup.server.api.ContributorServiceImpl
import com.calypsan.listenup.server.api.GenreServiceImpl
import com.calypsan.listenup.server.api.MoodServiceImpl
import com.calypsan.listenup.server.api.SearchServiceImpl
import com.calypsan.listenup.server.api.SeriesServiceImpl
import com.calypsan.listenup.server.api.TagServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.sync.BookMoodRepository
import com.calypsan.listenup.server.sync.BookSearchReindexer
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.MoodRepository
import com.calypsan.listenup.server.sync.TagRepository
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.cover.CoverResponder
import com.calypsan.listenup.server.cover.CoverStorage
import com.calypsan.listenup.server.cover.EmbeddedCoverCache
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser
import com.calypsan.listenup.server.scanner.metadata.MetadataPrecedence
import com.calypsan.listenup.server.services.AnalyzedBookMapper
import com.calypsan.listenup.server.services.BookGenreWriter
import com.calypsan.listenup.server.services.BookIngestPort
import com.calypsan.listenup.server.services.BookMoodWriter
import com.calypsan.listenup.server.services.BookPersister
import com.calypsan.listenup.server.services.BookPersisterMetrics
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreAutoCreator
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.services.PendingGenrePromotion
import com.calypsan.listenup.server.services.SearchReindexService
import com.calypsan.listenup.server.services.SeriesRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.nio.file.Path
import kotlinx.coroutines.flow.MutableSharedFlow
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Koin module for the books slice. Wires:
 *
 *  - [MeterRegistry] — a [SimpleMeterRegistry] backing [BookPersisterMetrics].
 *    There's no Prometheus scrape today; the counter is a countable diagnostic
 *    signal in logs, not a metrics pipeline (project "no premature
 *    observability" stance). [BookPersisterMetrics] is its only consumer.
 *  - [LibraryRegistry] — single-library id resolver (the real bootstrap is
 *    `Application.bootstrapLibraries`).
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
 *  - [CoverStorage] — filesystem-side counterpart for `BookService.deleteBookCover`.
 *  - [CoverImageStore] — the cover-scoped [ImageStore] rooted at
 *    `$LISTENUP_HOME/covers` (10 MiB cap). A distinct wrapper type so it doesn't
 *    collide with the avatar [ImageStore] in [profileModule].
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
 * @param metadataPrecedence the operator-configured textual-metadata precedence
 *   (resolved from `LISTENUP_METADATA_PRECEDENCE`). [LibraryRegistry] persists it
 *   onto the `libraries` row at bootstrap.
 * @param embeddedCoverCacheSize the operator-configured maximum number of covers
 *   the [EmbeddedCoverCache] retains (resolved from
 *   `LISTENUP_EMBEDDED_COVER_CACHE_SIZE`).
 * @param homeDir `$LISTENUP_HOME` as a [Path]; the covers sub-directory is resolved
 *   from it and passed to [CoverImageStore].
 */
fun booksModule(
    metadataPrecedence: MetadataPrecedence = MetadataPrecedence.DEFAULT,
    embeddedCoverCacheSize: Int = DEFAULT_EMBEDDED_COVER_CACHE_SIZE,
    homeDir: Path,
): Module =
    module {
        single<MeterRegistry> { SimpleMeterRegistry() }
        single { BookPersisterMetrics(get()) }

        single {
            LibraryRegistry(
                db = get(),
                metadataPrecedence = metadataPrecedence,
            )
        }

        single(createdAtStart = true) { ContributorRepository(get(), get(), get()) }
        single(createdAtStart = true) { SeriesRepository(get(), get(), get()) }
        single(createdAtStart = true) { GenreRepository(get(), get(), get()) }
        single { AnalyzedBookMapper(clock = get()) }
        single(createdAtStart = true) {
            BookRepository(
                get(),
                get(),
                get(),
                get(),
                get(),
                get<GenreRepository>(),
                get(),
                clock = get(),
                collectionBookRepository = get(),
                tagRepository = getOrNull<TagRepository>(),
                bookTagRepository = getOrNull<BookTagRepository>(),
                homeDir = homeDir,
                coverImageStore = get<CoverImageStore>(),
            )
        }
        single<BookIngestPort> { get<BookRepository>() }
        single { CoverStorage() }
        single { UserPermissionPolicy(db = get()) }
        single<BookService> {
            BookServiceImpl(
                repo = get<BookRepository>(),
                contributorRepo = get<ContributorRepository>(),
                seriesRepo = get<SeriesRepository>(),
                coverStorage = get<CoverStorage>(),
                db = get(),
                genreRepo = get<GenreRepository>(),
                accessPolicy = get<BookAccessPolicy>(),
                permissionPolicy = get<UserPermissionPolicy>(),
                principal = unscopedPlaceholder("BookService"),
                coverImageStore = get<CoverImageStore>(),
            )
        }
        single<ContributorService> {
            ContributorServiceImpl(
                contributorRepo = get(),
                bookRepo = get(),
                reindexer = get(),
                db = get(),
                permissionPolicy = get<UserPermissionPolicy>(),
                principal = unscopedPlaceholder("ContributorService"),
            )
        }
        single<SeriesService> {
            SeriesServiceImpl(
                seriesRepo = get(),
                bookRepo = get(),
                reindexer = get(),
                db = get(),
                permissionPolicy = get<UserPermissionPolicy>(),
                principal = unscopedPlaceholder("SeriesService"),
            )
        }
        single<SearchService> {
            SearchServiceImpl(
                db = get(),
                accessPolicy = get<BookAccessPolicy>(),
                principal = unscopedPlaceholder("SearchService"),
            )
        }
        single { BookSearchReindexer(get<BookTagRepository>(), get<TagRepository>(), get()) }
        single { SearchReindexService(db = get(), reindexer = get<BookSearchReindexer>()) }
        single<TagService> {
            TagServiceImpl(
                tagRepository = get<TagRepository>(),
                bookTagRepository = get<BookTagRepository>(),
                reindexer = get<BookSearchReindexer>(),
                db = get(),
                permissionPolicy = get<UserPermissionPolicy>(),
                principal = unscopedPlaceholder("TagService"),
            )
        }
        moodBindings()
        single<GenreService> {
            GenreServiceImpl(
                genreRepository = get<GenreRepository>(),
                bookRepository = get<BookRepository>(),
                reindexer = get<BookSearchReindexer>(),
                db = get(),
                permissionPolicy = get<UserPermissionPolicy>(),
                principal = unscopedPlaceholder("GenreService"),
            )
        }
        single { CollectionAccessPolicy(get(), get()) }
        single {
            CollectionServiceImpl(
                collectionRepo = get(),
                collectionBookRepo = get(),
                shareRepo = get(),
                accessPolicy = get(),
                permissionPolicy = get<UserPermissionPolicy>(),
                bus = get(),
                db = get(),
                clock = get(),
                principal = unscopedPlaceholder("CollectionService"),
            )
        }
        single<CollectionService> { get<CollectionServiceImpl>() }

        genreBootstrapBindings()
        coverAndPersisterBindings(embeddedCoverCacheSize, homeDir)
    }

/**
 * Moods slice bindings — the affective axis, mirroring tags (flat, syncable, soft-delete):
 *  - [BookMoodWriter] — scan/enrich-time find-or-create-then-link writer (add-only).
 *  - [MoodService] / [MoodServiceImpl] — the interactive curation surface.
 *  - [MoodDomainSeeder] — seeds the canonical Audible mood vocabulary on fresh installs
 *    (`isAlreadySeeded()` makes re-runs no-ops); invoked by `Application.launchSeeders`.
 *
 * The [MoodRepository] / [BookMoodRepository] syncable singletons live in [syncModule] so
 * the moods/book_moods domains register with `SyncRegistry` at bootstrap (like tags). Split
 * out of [booksModule] to keep that module body under the length budget.
 */
private fun Module.moodBindings() {
    single {
        BookMoodWriter(
            clock = get(),
            moodRepository = get<MoodRepository>(),
            bookMoodRepository = get<BookMoodRepository>(),
        )
    }
    single<MoodService> {
        MoodServiceImpl(
            moodRepository = get<MoodRepository>(),
            bookMoodRepository = get<BookMoodRepository>(),
            db = get(),
            permissionPolicy = get<UserPermissionPolicy>(),
            principal = unscopedPlaceholder("MoodService"),
        )
    }
    single { MoodDomainSeeder(db = get(), moodRepository = get<MoodRepository>()) }
}

/**
 * Boot-time genre bindings invoked by `Application.launchSeeders`:
 *  - [GenreDomainSeeder] — seeds the default taxonomy once per fresh install
 *    (`isAlreadySeeded()` makes re-runs no-ops).
 *  - [PendingGenrePromotion] — one-time drain of the legacy `pending_book_genres`
 *    backlog into live genres; idempotent, so a second boot costs a single
 *    empty-queue query.
 *
 * Both are logically part of the books slice; split out only to keep [booksModule]
 * under the length budget.
 */
private fun Module.genreBootstrapBindings() {
    single { GenreDomainSeeder(db = get(), genreRepository = get<GenreRepository>()) }
    single {
        PendingGenrePromotion(
            db = get(),
            bookGenreWriter = BookGenreWriter(get(), get(), GenreAutoCreator(get<GenreRepository>())),
        )
    }
}

/**
 * Cover-serving ([EmbeddedCoverCache], [CoverResponder]), managed-cover store
 * ([CoverImageStore]), and scan-ingest ([BookPersister]) bindings. Split out of
 * [booksModule] so the module body stays focused on the domain services; these are
 * the filesystem/scan-driven tail of the same slice.
 */
private fun Module.coverAndPersisterBindings(
    embeddedCoverCacheSize: Int,
    homeDir: Path,
) {
    single { CoverImageStore(ImageStore(homeDir.resolve("covers"), COVER_MAX_BYTES)) }
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
            libraryRepository = get(),
            collectionService = get<CollectionServiceImpl>(),
            db = get(),
            scanResultBus = get<MutableSharedFlow<ScanResult>>(named("scanResultBus")),
            eventBus = get<MutableSharedFlow<ScanEvent>>(),
            scope = get(),
            metrics = get(),
            coverImageStore = get<CoverImageStore>(),
        )
    }
}

/**
 * The unscoped-caller placeholder every principal-scoped service binding carries: a
 * [PrincipalProvider] that throws if invoked. Route handlers always [copyWith] the
 * authenticated principal before calling, so reaching this placeholder signals a wiring
 * bug — fail loud and early rather than silently serving an unscoped (over-broad) view.
 */
private fun unscopedPlaceholder(serviceName: String): PrincipalProvider =
    PrincipalProvider { error("Unscoped $serviceName — call copyWith(PrincipalProvider) at the route") }

/**
 * Default [EmbeddedCoverCache] capacity used when `scanner.embeddedCoverCacheSize`
 * is unset. Matches the cache's own built-in default.
 */
private const val DEFAULT_EMBEDDED_COVER_CACHE_SIZE = 1000

/** Maximum accepted cover image size (10 MiB). Covers are larger than avatars (2 MiB). */
private const val COVER_MAX_BYTES = 10L * 1024 * 1024
