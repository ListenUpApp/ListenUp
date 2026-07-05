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
import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.cover.CoverResponder
import com.calypsan.listenup.server.cover.CoverStorage
import com.calypsan.listenup.server.document.DocumentFileLocator
import com.calypsan.listenup.server.cover.EmbeddedCoverCache
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser
import com.calypsan.listenup.server.scanner.metadata.MetadataPrecedence
import com.calypsan.listenup.server.services.AnalyzedBookMapper
import com.calypsan.listenup.server.services.BookGenreWriter
import com.calypsan.listenup.server.services.BookIngestPort
import com.calypsan.listenup.server.services.BookMoodWriter
import com.calypsan.listenup.server.services.BookPersister
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreAutoCreator
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.services.PendingGenrePromotion
import com.calypsan.listenup.server.services.SearchReindexService
import com.calypsan.listenup.server.services.SeriesRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.io.files.Path
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin module for the books slice. Wires:
 *
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
        single {
            LibraryRegistry(
                sql = get(),
                metadataPrecedence = metadataPrecedence,
            )
        }

        // Contributor + Series are SQLDelight conversions (the cutover template):
        // they resolve [ListenUpDatabase], not the Exposed [Database] the other repos use.
        // Transitional: the merge/unmerge/delete service flows still wrap an Exposed
        // suspendTransaction around these SQLDelight writes, so those flows can hit
        // SQLITE_BUSY against these two domains until the merge-txn unit converts them.
        single(createdAtStart = true) { ContributorRepository(get<ListenUpDatabase>(), get(), get()) }
        single(createdAtStart = true) { SeriesRepository(get<ListenUpDatabase>(), get(), get()) }
        single(createdAtStart = true) { GenreRepository(get<ListenUpDatabase>(), get(), get()) }
        single { AnalyzedBookMapper(clock = get()) }
        single(createdAtStart = true) {
            BookRepository(
                db = get<ListenUpDatabase>(),
                bus = get(),
                registry = get(),
                driver = get<SqlDriver>(),
                contributorRepository = get(),
                seriesRepository = get(),
                genreRepository = get<GenreRepository>(),
                analyzedBookMapper = get(),
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
        single { UserPermissionPolicy(db = get<ListenUpDatabase>()) }
        single<BookService> {
            BookServiceImpl(
                repo = get<BookRepository>(),
                contributorRepo = get<ContributorRepository>(),
                seriesRepo = get<SeriesRepository>(),
                coverStorage = get<CoverStorage>(),
                sql = get<ListenUpDatabase>(),
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
                sqlDb = get<ListenUpDatabase>(),
                accessPolicy = get<BookAccessPolicy>(),
                permissionPolicy = get<UserPermissionPolicy>(),
                principal = unscopedPlaceholder("ContributorService"),
            )
        }
        single<SeriesService> {
            SeriesServiceImpl(
                seriesRepo = get(),
                bookRepo = get(),
                reindexer = get(),
                sqlDb = get<ListenUpDatabase>(),
                accessPolicy = get<BookAccessPolicy>(),
                permissionPolicy = get<UserPermissionPolicy>(),
                principal = unscopedPlaceholder("SeriesService"),
            )
        }
        searchBindings()
        single<TagService> {
            TagServiceImpl(
                tagRepository = get<TagRepository>(),
                bookTagRepository = get<BookTagRepository>(),
                reindexer = get<BookSearchReindexer>(),
                sql = get<ListenUpDatabase>(),
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
                sqlDb = get<ListenUpDatabase>(),
                accessPolicy = get<BookAccessPolicy>(),
                permissionPolicy = get<UserPermissionPolicy>(),
                principal = unscopedPlaceholder("GenreService"),
            )
        }
        single { CollectionAccessPolicy(get(), get()) }
        single {
            CollectionServiceImpl(
                collectionRepo = get(),
                collectionBookRepo = get(),
                grantRepo = get(),
                accessPolicy = get(),
                permissionPolicy = get<UserPermissionPolicy>(),
                bus = get(),
                sql = get<ListenUpDatabase>(),
                clock = get(),
                bookRevisionTouch = get<BookRepository>(),
                principal = unscopedPlaceholder("CollectionService"),
            )
        }
        single<CollectionService> { get<CollectionServiceImpl>() }

        genreBootstrapBindings()
        coverAndPersisterBindings(embeddedCoverCacheSize, homeDir)
    }

/**
 * Search-slice singletons — the [SearchService] (over SQLDelight via the shared [SqlDriver]),
 * the FTS reindexer, and the manual reindex trigger. Split out of [booksModule] to keep that
 * module body under the length budget.
 */
private fun Module.searchBindings() {
    single<SearchService> {
        SearchServiceImpl(
            db = get<ListenUpDatabase>(),
            driver = get<SqlDriver>(),
            accessPolicy = get<BookAccessPolicy>(),
            principal = unscopedPlaceholder("SearchService"),
        )
    }
    single { BookSearchReindexer(get(), get(), get<ListenUpDatabase>(), get<SqlDriver>()) }
    single {
        SearchReindexService(
            db = get<ListenUpDatabase>(),
            driver = get<SqlDriver>(),
            reindexer = get<BookSearchReindexer>(),
        )
    }
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
            sql = get<ListenUpDatabase>(),
            permissionPolicy = get<UserPermissionPolicy>(),
            principal = unscopedPlaceholder("MoodService"),
        )
    }
    single { MoodDomainSeeder(sql = get(), moodRepository = get<MoodRepository>()) }
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
    single { GenreDomainSeeder(genreRepository = get<GenreRepository>()) }
    single {
        PendingGenrePromotion(
            db = get<ListenUpDatabase>(),
            bookGenreWriter = BookGenreWriter(get<ListenUpDatabase>(), get(), GenreAutoCreator(get<GenreRepository>())),
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
    single { CoverImageStore(ImageStore(Path(homeDir, "covers"), COVER_MAX_BYTES)) }
    single {
        com.calypsan.listenup.server.scanner
            .CoverSpool(Path(homeDir, "scan-spool"))
    }
    single { EmbeddedCoverCache(maxSize = embeddedCoverCacheSize) }
    single {
        CoverResponder(
            repository = get<BookRepository>(),
            cache = get(),
            parser = get<EmbeddedMetadataParser>(),
        )
    }
    single { DocumentFileLocator(get<ListenUpDatabase>()) }

    single {
        BookPersister(
            ingest = get(),
            libraryRegistry = get(),
            libraryRepository = get(),
            collectionService = get<CollectionServiceImpl>(),
            sql = get<ListenUpDatabase>(),
            scanResultBus = get<MutableSharedFlow<ScanResult>>(EventBusQualifiers.ScanResults),
            eventBus = get<MutableSharedFlow<ScanEvent>>(EventBusQualifiers.ScanEvents),
            changeBus = get(),
            scope = get(),
            coverImageStore = get<CoverImageStore>(),
            coverSpool = get(),
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
