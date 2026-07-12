package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.dto.BookContributorInput
import com.calypsan.listenup.api.dto.BookGenreInput
import com.calypsan.listenup.api.dto.BookSeriesInput
import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.api.dto.ChapterInput
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.ChapterSource
import com.calypsan.listenup.api.error.CoverError
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.organize.OrganizeOnEditRelocator
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.api.sync.UserEditedField
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.cover.CoverInfo
import com.calypsan.listenup.server.cover.CoverStorage
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.TransactionLocal
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.sidecar.SidecarWriter
import com.calypsan.listenup.server.services.BookWriteExtras
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.services.contributorDedupKey
import com.calypsan.listenup.server.services.normalizeForDedup
import kotlinx.coroutines.withContext

private const val MAX_SEARCH_LIMIT = 200
private const val MAX_CONTRIBUTORS_PER_BOOK = 200
private const val MAX_SERIES_PER_BOOK = 200
private const val MAX_GENRES_PER_BOOK = 200
private const val MAX_CHAPTERS_PER_BOOK = 5000

/**
 * Thin [BookService] implementation. The work lives in [BookRepository]; this
 * class translates between the wire contract and the repository's public API.
 *
 * [updateBook] reads the current aggregate, applies the [BookUpdate] patch
 * field-by-field (null means "don't touch"), and writes the patched payload
 * through the syncable substrate so the revision bumps and the change-bus
 * fires uniformly. The read-then-write straddles two repository calls; both
 * run inside the same [suspendTransaction] so the substrate's own transaction
 * nests cleanly.
 *
 * [setBookContributors] and [setBookSeries] follow the same shape — read the
 * aggregate, resolve each input row to a stable child id (passing through the
 * corresponding `resolveOrCreate` when the input's id is null), write the
 * patched aggregate back through `repo.upsert`. The whole flow runs in one
 * [suspendTransaction] so any auto-created child rolls back if the book upsert
 * fails. List position carries on the list index — `BookRepository.replaceSeries`
 * stamps `ordinal` from the index, so callers sort by [BookSeriesInput.position]
 * before mapping to the wire payload.
 *
 * [deleteBookCover] is a content edit (gated on `canEdit` like the other four
 * mutations) and the one mutation that touches the filesystem.
 * It resolves the cover file path via [BookRepository.coverInfo] up front,
 * nullifies the book's cover columns inside a [suspendTransaction] (the
 * revision-bump and change-bus fire atomically with the row update), then
 * delegates the file delete to [CoverStorage] **after** the transaction
 * commits — the DB row is the source of truth, and a flaky filesystem can't
 * roll back a successful nullify. Embedded covers carry no file of their own
 * (the artwork lives inside the audio file), so the post-commit delete is a
 * no-op for them.
 *
 * [getBook] is gated through [BookAccessPolicy]: the authenticated caller is
 * resolved from [principal] (never from request fields — prevents spoofing),
 * and a book the caller can't reach is reported as `SyncError.NotFound`, the
 * same shape as an absent book, so the gate never leaks the existence of a
 * book in a collection the caller can't see. Route handlers call [copyWith] to
 * bind each request to the authenticated [UserPrincipal][com.calypsan.listenup.server.auth.UserPrincipal];
 * the Koin singleton carries an unscoped placeholder that throws on
 * [PrincipalProvider.current] to catch misuse early.
 */
internal class BookServiceImpl(
    private val repo: BookRepository,
    private val contributorRepo: ContributorRepository,
    private val seriesRepo: SeriesRepository,
    private val coverStorage: CoverStorage,
    private val sql: ListenUpDatabase,
    private val genreRepo: com.calypsan.listenup.server.services.GenreRepository,
    private val accessPolicy: BookAccessPolicy,
    private val permissionPolicy: UserPermissionPolicy,
    private val principal: PrincipalProvider,
    private val coverImageStore: CoverImageStore? = null,
    private val organizeRelocator: OrganizeOnEditRelocator? = null,
    private val sidecarWriter: SidecarWriter? = null,
) : BookService {
    override suspend fun getBook(id: BookId): AppResult<BookSyncPayload> {
        val p =
            principal.current()
                ?: return AppResult.Failure(SyncError.NotFound(domain = "book", entityId = id.value))
        val payload =
            repo.findById(id)
                ?: return AppResult.Failure(SyncError.NotFound(domain = "book", entityId = id.value))
        if (!accessPolicy.canAccess(p.userId.value, p.role, id.value)) {
            // Report a denied book as absent — never leak its existence.
            return AppResult.Failure(SyncError.NotFound(domain = "book", entityId = id.value))
        }
        return AppResult.Success(payload)
    }

    /**
     * Returns the [AppError] denial when the caller lacks the `canEdit` permission, or null when
     * the edit is allowed. Exposed as `internal` so the cover-upload route can gate before buffering
     * the multipart body — `setBookCover` also calls `requireCanEdit()` internally as defense-in-depth.
     */
    internal suspend fun checkCanEdit(): AppError? = requireCanEdit()

    /** Returns a copy scoped to the given [principal]. Route handlers call this per-request. */
    fun copyWith(principal: PrincipalProvider): BookServiceImpl =
        BookServiceImpl(
            repo = repo,
            contributorRepo = contributorRepo,
            seriesRepo = seriesRepo,
            coverStorage = coverStorage,
            sql = sql,
            genreRepo = genreRepo,
            accessPolicy = accessPolicy,
            permissionPolicy = permissionPolicy,
            principal = principal,
            coverImageStore = coverImageStore,
            organizeRelocator = organizeRelocator,
            sidecarWriter = sidecarWriter,
        )

    override suspend fun searchBooks(
        query: String,
        limit: Int,
    ): AppResult<List<BookId>> {
        if (query.isBlank()) return AppResult.Success(emptyList())
        // Gate the FTS id set to the viewer's reachable books — an inaccessible match must
        // never leak its existence (and from there feed cover/prepare). The caller is resolved
        // from [principal] (never request fields); ROOT/ADMIN get a null filter (unfiltered).
        val access = principal.current()?.let { accessPolicy.accessibleBookIdsSql(it.userId.value, it.role) }
        return AppResult.Success(repo.searchFts(query, limit.coerceIn(1, MAX_SEARCH_LIMIT), access))
    }

    /**
     * Content-metadata edits are gated on the per-user `canEdit` flag. ROOT/ADMIN pass
     * implicitly; a MEMBER passes iff their flag is set (fresh DB lookup per call). An
     * absent principal — a wiring bug, since route handlers always [copyWith] the
     * authenticated caller — is denied with [AuthError.PermissionDenied][com.calypsan.listenup.api.error.AuthError.PermissionDenied].
     * Returns null when the edit is permitted; the denial to surface otherwise.
     */
    private suspend fun requireCanEdit(): AppError? {
        val p = principal.current() ?: return AuthError.PermissionDenied()
        return permissionPolicy.requireCanEdit(p.userId, p.role)
    }

    override suspend fun updateBook(
        id: BookId,
        patch: BookUpdate,
    ): AppResult<Unit> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        val current =
            repo.findById(id)
                ?: return bookNotFound(id)
        val patched = current.applyPatch(patch)
        // An added-date edit must re-stamp createdAt, which writePayload only writes when this
        // override is present — keeping rescans (which carry a placeholder createdAt) from clobbering it.
        val upsertResult =
            if (patch.addedAt != null) {
                withContext(TransactionLocal(BookWriteExtras(createdAtOverride = patch.addedAt))) {
                    repo.upsert(patched)
                }
            } else {
                repo.upsert(patched)
            }
        return when (upsertResult) {
            is AppResult.Success -> {
                // A title edit may change the book's canonical folder — let the organizer replan
                // (debounced no-op when disabled or when the path is unchanged).
                organizeRelocator?.onBookEdited(id)
                // Curation changed — schedule the debounced listenup.json write-through
                // (post-commit: upsert's transaction has already committed by here).
                sidecarWriter?.markDirty(id.value)
                AppResult.Success(Unit)
            }

            is AppResult.Failure -> {
                AppResult.Failure(upsertResult.error)
            }
        }
    }

    override suspend fun setBookContributors(
        id: BookId,
        contributors: List<BookContributorInput>,
    ): AppResult<Unit> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        if (contributors.size > MAX_CONTRIBUTORS_PER_BOOK) {
            return AppResult.Failure(
                BookError.InvalidInput(
                    debugInfo = "contributors: size ${contributors.size} exceeds max $MAX_CONTRIBUTORS_PER_BOOK",
                ),
            )
        }
        val current =
            repo.findById(id)
                ?: return bookNotFound(id)
        val sorted = contributors.sortedBy { it.position }
        // Resolve every id-less contributor in ONE bulk SELECT (creating the missing rows through the
        // same resolveOrCreate the scanner uses), then look each input's id up by its dedup key —
        // identical ids, order, duplicate-collapse, and sync events to the prior per-item path.
        val resolvedByKey = contributorRepo.resolveOrCreateAll(sorted.filter { it.id == null }.map { it.name to null })
        val resolved =
            sorted.map { input ->
                val resolvedId =
                    input.id?.value
                        ?: resolvedByKey.getValue(contributorDedupKey(input.name, null)).value
                BookContributorPayload(
                    id = resolvedId,
                    name = input.name,
                    sortName = null,
                    role = input.role,
                    creditedAs = input.creditedAs,
                )
            }
        // Record CONTRIBUTORS provenance so a later rescan preserves this edit (the merge in
        // BookRepository skips replaceContributors while the field stays in the sticky set).
        val patched =
            current.copy(
                contributors = resolved,
                userEditedFields = current.userEditedFields + UserEditedField.CONTRIBUTORS,
            )
        return when (val upsertResult = repo.upsert(patched)) {
            is AppResult.Success -> {
                // The primary author is a canonical-path segment — organizer replan (see updateBook).
                organizeRelocator?.onBookEdited(id)
                sidecarWriter?.markDirty(id.value)
                AppResult.Success(Unit)
            }

            is AppResult.Failure -> {
                AppResult.Failure(upsertResult.error)
            }
        }
    }

    override suspend fun setBookChapters(
        id: BookId,
        chapters: List<ChapterInput>,
    ): AppResult<Unit> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        if (chapters.size > MAX_CHAPTERS_PER_BOOK) {
            return AppResult.Failure(
                BookError.InvalidInput(
                    debugInfo = "chapters: size ${chapters.size} exceeds max $MAX_CHAPTERS_PER_BOOK",
                ),
            )
        }
        val current = repo.findById(id) ?: return bookNotFound(id)
        validateChapterSet(chapters, current.totalDuration)?.let { return AppResult.Failure(it) }
        val payloadChapters =
            chapters.map {
                BookChapterPayload(
                    id = it.id,
                    title = it.title,
                    duration = it.duration,
                    startTime = it.startTime,
                    partTitle = it.partTitle,
                    bookTitle = it.bookTitle,
                )
            }
        return when (
            val res =
                repo.upsert(
                    current.copy(chapters = payloadChapters, chapterSource = ChapterSource.USER),
                )
        ) {
            is AppResult.Success -> {
                sidecarWriter?.markDirty(id.value)
                AppResult.Success(Unit)
            }

            is AppResult.Failure -> {
                AppResult.Failure(res.error)
            }
        }
    }

    /** Set-level invariants (per-row shape is already checked by ChapterInput.init). Null = valid. */
    private fun validateChapterSet(
        chapters: List<ChapterInput>,
        bookDurationMs: Long,
    ): AppError? {
        if (chapters.isEmpty()) return null
        val starts = chapters.map { it.startTime }
        if (starts != starts.sorted() || starts.toSet().size != starts.size) {
            return BookError.InvalidInput(debugInfo = "chapter starts must be strictly increasing")
        }
        if (chapters.any { it.startTime >= bookDurationMs }) {
            return BookError.InvalidInput(debugInfo = "chapter start beyond book duration")
        }
        return null
    }

    override suspend fun setBookSeries(
        id: BookId,
        series: List<BookSeriesInput>,
    ): AppResult<Unit> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        if (series.size > MAX_SERIES_PER_BOOK) {
            return AppResult.Failure(
                BookError.InvalidInput(
                    debugInfo = "series: size ${series.size} exceeds max $MAX_SERIES_PER_BOOK",
                ),
            )
        }
        val current =
            repo.findById(id)
                ?: return bookNotFound(id)
        val sorted = series.sortedWith(compareBy(nullsLast()) { it.position })
        // Resolve every id-less series in ONE bulk SELECT, then look each input's id up by its
        // normalized key — identical ids, order, and sync events to the prior per-item path.
        val resolvedByKey = seriesRepo.resolveOrCreateAll(sorted.filter { it.id == null }.map { it.name })
        val resolved =
            sorted.map { input ->
                val resolvedId =
                    input.id?.value
                        ?: resolvedByKey.getValue(normalizeForDedup(input.name)).value
                BookSeriesPayload(
                    id = resolvedId,
                    name = input.name,
                    sequence = input.position?.toString(),
                )
            }
        // Record SERIES provenance so a later rescan preserves this edit (the merge in BookRepository
        // skips replaceSeries while the field stays in the sticky set).
        val patched =
            current.copy(
                series = resolved,
                userEditedFields = current.userEditedFields + UserEditedField.SERIES,
            )
        return when (val upsertResult = repo.upsert(patched)) {
            is AppResult.Success -> {
                // Series name/sequence are canonical-path segments — organizer replan (see updateBook).
                organizeRelocator?.onBookEdited(id)
                sidecarWriter?.markDirty(id.value)
                AppResult.Success(Unit)
            }

            is AppResult.Failure -> {
                AppResult.Failure(upsertResult.error)
            }
        }
    }

    override suspend fun setBookGenres(
        id: BookId,
        genres: List<BookGenreInput>,
    ): AppResult<Unit> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        if (genres.size > MAX_GENRES_PER_BOOK) {
            return AppResult.Failure(
                BookError.InvalidInput(
                    debugInfo = "genres: size ${genres.size} exceeds max $MAX_GENRES_PER_BOOK",
                ),
            )
        }
        // The genre relink and the book re-upsert are independent SQLDelight writes. They run
        // SEQUENTIALLY — the relink commits, then the re-upsert runs. The genre junction re-derives
        // on the next book read, so the re-upsert (which only bumps the revision + publishes) sees
        // the relinked genres.
        val current = repo.findById(id) ?: return bookNotFound(id)

        // Validate every input genre exists and is live BEFORE the relink. Unknown ids surface as
        // BookError.InvalidInput (no auto-create). One bulk read replaces the per-input
        // findById storm; the in-order membership check keeps the first-offender (input order) error
        // byte-identical. genreRepo.findLiveIds is a suspend read, so the validation runs before
        // opening the (non-suspend) SQLDelight relink transaction.
        val liveIds = genreRepo.findLiveIds(genres.map { it.genreId.value })
        for (input in genres) {
            if (input.genreId.value !in liveIds) {
                return AppResult.Failure(BookError.InvalidInput(debugInfo = "unknownGenre=${input.genreId.value}"))
            }
        }

        // Relink the junction wholesale (delete-by-book then insert-if-absent per distinct id) in a
        // single SQLDelight transaction, mirroring the prior BookGenreTable.relinkBookGenres.
        suspendTransaction(sql) {
            sql.bookGenresQueries.deleteByBookId(id.value)
            for (genreId in genres.map { it.genreId.value }.distinct()) {
                sql.bookGenresQueries.insertIfAbsent(book_id = id.value, genre_id = genreId)
            }
        }

        // Re-upsert the book so the substrate bumps revision + publishes `book.Updated`. The book
        // payload's `genres` field re-derives from the live junction on read.
        return when (val upsertResult = repo.upsert(current)) {
            is AppResult.Success -> AppResult.Success(Unit)
            is AppResult.Failure -> AppResult.Failure(upsertResult.error)
        }
    }

    /**
     * Validates and stores [bytes] as the book's managed cover, then records the path + hash in
     * [BookRepository.setManagedCover] with [CoverSource.UPLOADED].
     *
     * Gated on [requireCanEdit] — only ROOT/ADMIN or a MEMBER with the `canEdit` flag may
     * upload a cover. Returns [AppResult.Failure] with [AuthError.PermissionDenied] when denied.
     *
     * The stored relative path follows the shape `covers/<bookId>.<ext>` (e.g. `covers/abc123.png`),
     * which matches the sandbox the cover-serving route resolves under [homeDir].
     *
     * Note: this method is **not** on the [BookService] @Rpc interface — cover bytes are binary,
     * so the upload goes through a dedicated multipart REST route rather than the RPC surface.
     *
     * @throws IllegalStateException when [coverImageStore] is not wired (library not configured).
     */
    internal suspend fun setBookCover(
        id: BookId,
        bytes: ByteArray,
        contentType: String,
    ): AppResult<Unit> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        val store = coverImageStore ?: error("CoverImageStore not wired — library must be configured")
        val stored = store.store.store(id.value, bytes, contentType)
        // Derive the repo-relative path from the stored absolute path's filename only.
        // stored.path lives under homeDir/covers/<bookId>.<ext>; the repo stores covers/<filename>.
        val relPath = "covers/${stored.path.name}"
        return repo.setManagedCover(id, relPath, stored.sha256, CoverSource.UPLOADED)
    }

    override suspend fun deleteBookCover(id: BookId): AppResult<Unit> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        // Validate the book exists and has a cover before touching anything.
        // Read the payload first to determine the cover source (authoritative) and
        // whether the cover is managed (UPLOADED/ENRICHED) or filesystem-side.
        val current = repo.findById(id) ?: return bookNotFound(id)
        if (current.cover == null) {
            return AppResult.Failure(CoverError.NotPresent(debugInfo = "bookId=${id.value}"))
        }

        val coverSource = current.cover!!.source
        // Managed covers live in $LISTENUP_HOME/covers/ — determined by cover source,
        // not by whether coverInfo() resolves (which depends on homeDir being configured).
        val isManagedCover = coverSource == CoverSource.UPLOADED || coverSource == CoverSource.ENRICHED

        // Resolve the filesystem path for non-managed covers BEFORE the transaction.
        // Embedded covers have no standalone file; Filesystem covers do.
        val filesystemPath =
            if (!isManagedCover) {
                (repo.coverInfo(id) as? CoverInfo.Filesystem)?.path
            } else {
                null
            }

        val result: AppResult<Unit> =
            if (isManagedCover) {
                // Managed covers (UPLOADED, ENRICHED): use clearManagedCover which bypasses
                // the sticky-upload guard in writePayload — the guard prevents re-scan from
                // clobbering a user-uploaded cover, but an explicit delete must always win.
                repo.clearManagedCover(id)
            } else {
                // Filesystem / Embedded covers: upsert with cover = null to clear the source
                // column. writePayload skips coverPath/coverHash for non-managed payloads, so
                // the null propagates cleanly.
                val fresh = repo.findById(id)
                if (fresh == null) {
                    bookNotFound(id)
                } else {
                    when (val upsertResult = repo.upsert(fresh.copy(cover = null))) {
                        is AppResult.Success -> AppResult.Success(Unit)
                        is AppResult.Failure -> AppResult.Failure(upsertResult.error)
                    }
                }
            }
        if (result is AppResult.Success) {
            // Delete the standalone file — after the DB commit so a failed file delete
            // never rolls back a successful nullify.
            if (filesystemPath != null) {
                coverStorage.delete(filesystemPath)
            }
            // Remove the managed file from $LISTENUP_HOME/covers/ using the bookId as
            // the key — ImageStore.delete probes all extensions (jpg, png, webp).
            if (isManagedCover) {
                coverImageStore?.store?.delete(id.value)
            }
        }
        return result
    }
}

/**
 * Constructs a [BookService] backed by [BookServiceImpl]. Public so cross-module
 * test harnesses (e.g. `:sharedLogic:jvmTest`'s `WithClientSyncEngineAgainstServer`)
 * can build the service without depending on the Koin graph or piercing the
 * `internal` access on [BookServiceImpl]. Production wiring continues to construct
 * the impl directly inside the books Koin module.
 */
fun createBookService(
    repo: BookRepository,
    contributorRepo: ContributorRepository,
    seriesRepo: SeriesRepository,
    coverStorage: CoverStorage,
    sql: com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase,
    driver: app.cash.sqldelight.db.SqlDriver,
    genreRepo: com.calypsan.listenup.server.services.GenreRepository,
    principal: PrincipalProvider =
        PrincipalProvider { error("Unscoped BookService — call bookServiceScopedTo at the route") },
): BookService =
    BookServiceImpl(
        repo,
        contributorRepo,
        seriesRepo,
        coverStorage,
        sql,
        genreRepo,
        BookAccessPolicy(db = sql, driver = driver),
        UserPermissionPolicy(sql),
        principal,
    )

/**
 * Scopes a [BookService] built by [createBookService] to [principal] for one request.
 * Public so cross-module test harnesses can bind the authenticated caller without
 * piercing the `internal` access on [BookServiceImpl] or its [BookServiceImpl.copyWith].
 * Production wiring calls [BookServiceImpl.copyWith] directly in the RPC route.
 */
fun bookServiceScopedTo(
    service: BookService,
    principal: PrincipalProvider,
): BookService = (service as BookServiceImpl).copyWith(principal)

private fun bookNotFound(id: BookId): AppResult.Failure =
    AppResult.Failure(BookError.NotFound(debugInfo = "bookId=${id.value}"))

private fun BookSyncPayload.applyPatch(patch: BookUpdate): BookSyncPayload =
    copy(
        title = patch.title ?: title,
        sortTitle = patch.sortTitle ?: sortTitle,
        subtitle = patch.subtitle ?: subtitle,
        description = patch.description ?: description,
        publisher = patch.publisher ?: publisher,
        publishYear = patch.publishYear ?: publishYear,
        language = patch.language ?: language,
        isbn = patch.isbn ?: isbn,
        asin = patch.asin ?: asin,
        abridged = patch.abridged ?: abridged,
        // The added date is stored as createdAt; null leaves it untouched. The DB write is gated by
        // BookWriteExtras.createdAtOverride in writePayload so only this edit path can move it.
        createdAt = patch.addedAt ?: createdAt,
        // Record per-field user-edit provenance so a later rescan preserves these hand-edits instead
        // of re-deriving them from the files. Each edited scalar is added to the sticky set; the merge
        // in BookRepository keeps it protected from then on.
        userEditedFields =
            userEditedFields +
                buildSet {
                    if (patch.title != null) add(UserEditedField.TITLE)
                    if (patch.subtitle != null) add(UserEditedField.SUBTITLE)
                    if (patch.description != null) add(UserEditedField.DESCRIPTION)
                },
    )
