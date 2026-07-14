package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.MetadataLookupService
import com.calypsan.listenup.api.dto.CoverSearchResults
import com.calypsan.listenup.api.dto.MetadataApplySelection
import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.dto.MetadataChapters
import com.calypsan.listenup.api.dto.MetadataContributorHit
import com.calypsan.listenup.api.dto.MetadataContributorProfile
import com.calypsan.listenup.api.dto.MetadataSearchResults
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.metadata.audible.toAudibleRegion
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.metadata.EnrichmentCoordinator
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.metadata.spi.ContributorMeta
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.CoverSearchService
import com.calypsan.listenup.server.services.GenreAutoCreator
import com.calypsan.listenup.server.services.GenreHierarchyFromLadder
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.services.MetadataService
import com.calypsan.listenup.server.services.SeriesRepository
import kotlinx.coroutines.CancellationException

/**
 * Server-side implementation of [MetadataLookupService].
 *
 * Search / fetch / refresh reads compose across the metadata provider registry through the injected
 * [coordinator], which walks each domain's configured provider chain first-non-empty (Audible core +
 * iTunes cover fallback + genres today) and returns a provider-neutral result; this service projects
 * that onto the wire DTOs via [toMetadataBook] / [toMetadataChapters]. The two apply methods
 * ([applyBookMetadata], [applyContributorMetadata]) write through the syncable substrate via
 * [BookRepository] and [ContributorRepository]. Contributor search + profile lookup route through the
 * same [coordinator] (served by Audnexus's [com.calypsan.listenup.server.metadata.spi.ContributorSource]).
 *
 * Search / fetch reads ([searchBooks], [getBookMetadata], [getBookChapters],
 * [searchContributorMetadata], [getContributorMetadata]) are open to any authenticated
 * user. The state-changing / privileged operations — [applyBookMetadata],
 * [applyContributorMetadata] (write through the syncable substrate) and
 * [refreshBookMetadata] (force a fresh, rate-limited external fetch, bypassing the cache) —
 * are gated on the per-user `canEdit` flag via [permissionPolicy]: ROOT/ADMIN pass
 * implicitly, a MEMBER passes iff their flag is set (fresh DB lookup per call). The
 * authenticated caller is resolved from [principal] — route handlers call [copyWith] to bind
 * it per-request; the Koin singleton carries an unscoped placeholder that yields no
 * principal, so an absent principal on a gated op is a wiring bug and is denied.
 */
internal class MetadataLookupServiceImpl(
    private val metadataService: MetadataService,
    private val coordinator: EnrichmentCoordinator,
    private val coverSearchService: CoverSearchService,
    private val bookRepository: BookRepository,
    private val contributorRepository: ContributorRepository,
    private val seriesRepository: SeriesRepository,
    private val imageDeps: MetadataImageDeps,
    private val enrichmentDeps: MetadataEnrichmentDeps,
    private val permissionPolicy: UserPermissionPolicy,
    private val sqlDb: ListenUpDatabase,
    private val genreRepository: GenreRepository,
    private val principal: PrincipalProvider = PrincipalProvider.None,
) : MetadataLookupService {
    /** Returns a copy scoped to the given [principal]. Route handlers call this per-request. */
    fun copyWith(principal: PrincipalProvider): MetadataLookupServiceImpl =
        MetadataLookupServiceImpl(
            metadataService = metadataService,
            coordinator = coordinator,
            coverSearchService = coverSearchService,
            bookRepository = bookRepository,
            contributorRepository = contributorRepository,
            seriesRepository = seriesRepository,
            imageDeps = imageDeps,
            enrichmentDeps = enrichmentDeps,
            permissionPolicy = permissionPolicy,
            sqlDb = sqlDb,
            genreRepository = genreRepository,
            principal = principal,
        )

    /**
     * Content-metadata edits are gated on the per-user `canEdit` flag. ROOT/ADMIN pass
     * implicitly; a MEMBER passes iff their flag is set (fresh DB lookup per call). An
     * absent principal — a wiring bug, since route handlers always [copyWith] the
     * authenticated caller — is denied. Returns null when permitted; the denial otherwise.
     */
    private suspend fun requireCanEdit(): AppError? {
        val p = principal.current() ?: return AuthError.PermissionDenied()
        return permissionPolicy.requireCanEdit(p.userId, p.role)
    }

    override suspend fun searchBooks(
        query: String,
        region: MetadataLocale?,
    ): AppResult<MetadataSearchResults> {
        val locale = region ?: MetadataLocale.DEFAULT
        val hits = coordinator.searchBooks(query, locale).map { it.toMetadataBook() }
        return AppResult.Success(MetadataSearchResults(hits = hits))
    }

    override suspend fun getBookMetadata(
        asin: String,
        region: MetadataLocale,
    ): AppResult<MetadataBook?> = AppResult.Success(composeMetadataBook(asin, region))

    override suspend fun getBookChapters(
        asin: String,
        region: MetadataLocale,
    ): AppResult<MetadataChapters?> =
        AppResult.Success(coordinator.composeChapters(bookIdentity(asin), region)?.toMetadataChapters())

    /**
     * Contributor auto-match — search + profile fetch — composed across the provider registry through
     * the [coordinator]. The CONTRIBUTORS chain is served by Audnexus's `ContributorSource` (Audible has
     * no contributor-profile endpoint), so the wizard resolves author bios and photos from Audnexus.
     * A total catalog miss returns an empty list / null, so the UI cleanly shows "no matches" and manual
     * contributor editing stays the fallback (Never-Stranded).
     */
    override suspend fun searchContributorMetadata(query: String): AppResult<List<MetadataContributorHit>> =
        AppResult.Success(
            coordinator.searchContributors(query, MetadataLocale.DEFAULT).map {
                MetadataContributorHit(asin = it.key, name = it.name)
            },
        )

    override suspend fun getContributorMetadata(
        asin: String,
        region: MetadataLocale,
    ): AppResult<MetadataContributorProfile?> =
        AppResult.Success(coordinator.getContributor(asin, region)?.toMetadataContributorProfile())

    override suspend fun refreshBookMetadata(
        asin: String,
        region: MetadataLocale,
    ): AppResult<MetadataBook?> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        return AppResult.Success(composeMetadataBook(asin, region, refresh = true))
    }

    /** Composes and projects the ASIN-keyed book preview; `null` when no catalog has the book. */
    private suspend fun composeMetadataBook(
        asin: String,
        locale: MetadataLocale,
        refresh: Boolean = false,
    ): MetadataBook? = coordinator.composeBook(bookIdentity(asin), locale, refresh)?.toMetadataBook()

    /**
     * The lookup key for an ASIN-keyed compose. The title is unknown at this point — the coordinator
     * backfills it from the fetched core before running any title-keyed (cover) lookup.
     */
    private fun bookIdentity(asin: String): BookIdentity = BookIdentity(asin = asin, title = "")

    override suspend fun applyBookMetadata(
        bookId: BookId,
        asin: String,
        region: MetadataLocale,
        selection: MetadataApplySelection,
    ): AppResult<Unit> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        val genreAutoCreator = GenreAutoCreator(genreRepository)
        return BookMetadataApplier(
            bookRepository = bookRepository,
            contributorRepository = contributorRepository,
            seriesRepository = seriesRepository,
            imageStorage = imageDeps.imageStorage,
            coverImageStore = imageDeps.coverImageStore,
            matchSource = { a, r -> AppResult.Success(composeMetadataBook(a, MetadataLocale(r.code))) },
            enrichmentProvider = MetadataProviderId.AUDIBLE.value,
            genreHierarchy = GenreHierarchyFromLadder(sqlDb, genreRepository, genreAutoCreator),
            sqlDb = sqlDb,
            ladderSource = { r, a ->
                when (val book = metadataService.getBook(r, a)) {
                    is AppResult.Success -> book.data?.genreLadders.orEmpty()
                    is AppResult.Failure -> emptyList()
                }
            },
            enrichmentDeps = enrichmentDeps,
        ).apply(bookId, asin, region.toAudibleRegion(), selection)
    }

    override suspend fun applyChapterNames(
        bookId: BookId,
        asin: String,
        region: MetadataLocale,
        ordinals: Set<Int>,
    ): AppResult<Unit> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        return ChapterNameApplier(
            bookRepository = bookRepository,
            metadataService = metadataService,
        ).apply(bookId, asin, region.toAudibleRegion(), ordinals)
    }

    override suspend fun applyContributorMetadata(
        contributorId: ContributorId,
        asin: String,
        region: MetadataLocale,
    ): AppResult<Unit> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        return ContributorMetadataApplier(
            contributorRepository = contributorRepository,
            imageStorage = imageDeps.imageStorage,
            coordinator = coordinator,
            imageHome = imageDeps.imageHome,
        ).apply(contributorId, asin, region)
    }

    override suspend fun searchCovers(
        bookId: BookId,
        region: MetadataLocale?,
    ): AppResult<CoverSearchResults> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        return coverSearchService.searchCovers(bookId, region).map { CoverSearchResults(options = it) }
    }

    override suspend fun applyCover(
        bookId: BookId,
        url: String,
    ): AppResult<Unit> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        // Validate the book exists before fetching/storing, so an unknown id can't leave an
        // orphaned cover file on disk (the store keys on bookId, but setManagedCover would fail).
        bookRepository.findById(bookId)
            ?: return AppResult.Failure(MetadataError.NotFound(debugInfo = "no book for id ${bookId.value}"))
        return try {
            val bytes = imageDeps.imageStorage.downloadBytes(url)
            val stored = imageDeps.coverImageStore.store.store(bookId.value, bytes, "image/jpeg")
            val relPath = "covers/${stored.path.name}"
            bookRepository.setManagedCover(bookId, relPath, stored.sha256, CoverSource.UPLOADED)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ImageStore.InvalidImageException) {
            // The fetched bytes are not a usable image — user must pick a different URL.
            // Non-retryable: re-firing the same call against the same URL can't succeed.
            AppResult.Failure(MetadataError.Malformed(debugInfo = "cover bytes rejected: ${e.message}"))
        } catch (e: Exception) {
            AppResult.Failure(
                MetadataError.ExternalUnavailable(debugInfo = "cover download/store failed: ${e.message}"),
            )
        }
    }
}

// ─── Internal → wire DTO mappers ─────────────────────────────────────────────

private fun ContributorMeta.toMetadataContributorProfile(): MetadataContributorProfile =
    MetadataContributorProfile(
        asin = key,
        name = name,
        sortName = null,
        description = description,
        imageUrl = imageUrl,
        birthDate = null,
        deathDate = null,
        // Audnexus author profiles expose no birth/death date, sort name, or website (only name,
        // description, and photo), so those stay null — website remains a manual-only field edited
        // on the contributor page.
        website = null,
    )
