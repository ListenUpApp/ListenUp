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
import com.calypsan.listenup.api.metadata.AudibleRegion
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.metadata.audible.AudibleContributorProfile
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.metadata.provider.MetadataProvider
import com.calypsan.listenup.server.metadata.provider.MetadataSource
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.CoverSearchService
import com.calypsan.listenup.server.services.GenreAutoCreator
import com.calypsan.listenup.server.services.GenreHierarchyFromLadder
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.MetadataService
import com.calypsan.listenup.server.services.SeriesRepository
import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * Server-side implementation of [MetadataLookupService].
 *
 * Delegates search/fetch/refresh to the injected [metadataProviders] list, which own
 * the catalog-specific mapping. The two apply methods ([applyBookMetadata],
 * [applyContributorMetadata]) write through the syncable substrate via [BookRepository]
 * and [ContributorRepository]. Contributor lookup paths still use [metadataService]
 * directly (contributor search + profile fetch are Audible-only, not provider-abstracted yet).
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
    private val metadataProviders: List<MetadataProvider>,
    private val coverSearchService: CoverSearchService,
    private val bookRepository: BookRepository,
    private val contributorRepository: ContributorRepository,
    private val seriesRepository: SeriesRepository,
    private val imageDeps: MetadataImageDeps,
    private val permissionPolicy: UserPermissionPolicy,
    private val db: Database,
    private val genreRepository: GenreRepository,
    private val defaultRegion: AudibleRegion = AudibleRegion.US,
    private val principal: PrincipalProvider = PrincipalProvider.None,
) : MetadataLookupService {
    /** The Audible provider handles ASIN-keyed reads (get/refresh/chapters). */
    private val audible =
        metadataProviders.first {
            it.source == MetadataSource.AUDIBLE
        }

    /** Returns a copy scoped to the given [principal]. Route handlers call this per-request. */
    fun copyWith(principal: PrincipalProvider): MetadataLookupServiceImpl =
        MetadataLookupServiceImpl(
            metadataService = metadataService,
            metadataProviders = metadataProviders,
            coverSearchService = coverSearchService,
            bookRepository = bookRepository,
            contributorRepository = contributorRepository,
            seriesRepository = seriesRepository,
            imageDeps = imageDeps,
            permissionPolicy = permissionPolicy,
            db = db,
            genreRepository = genreRepository,
            defaultRegion = defaultRegion,
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
        region: AudibleRegion?,
    ): AppResult<MetadataSearchResults> {
        val hits =
            buildList {
                for (provider in metadataProviders) {
                    when (val r = provider.search(query, region)) {
                        is AppResult.Failure -> return r
                        is AppResult.Success -> addAll(r.data)
                    }
                }
            }
        return AppResult.Success(MetadataSearchResults(hits = hits))
    }

    override suspend fun getBookMetadata(
        asin: String,
        region: AudibleRegion,
    ): AppResult<MetadataBook?> = audible.getBook(asin, region).enrichWithItunesCover()

    override suspend fun getBookChapters(
        asin: String,
        region: AudibleRegion,
    ): AppResult<MetadataChapters?> = audible.getChapters(asin, region)

    /**
     * Searches Audible for contributors matching [query] using HTML scraping of
     * `www.audible.com/search?searchAuthor={query}`. Results are deduplicated by
     * ASIN and mapped to [MetadataContributorHit] wire DTOs.
     *
     * Backed by [MetadataService.searchContributors], which adds TTL caching
     * so repeated queries for the same name avoid redundant scraping.
     */
    override suspend fun searchContributorMetadata(query: String): AppResult<List<MetadataContributorHit>> =
        metadataService
            .searchContributors(defaultRegion, query)
            .map { profiles -> profiles.map { MetadataContributorHit(asin = it.asin, name = it.name) } }

    override suspend fun getContributorMetadata(
        asin: String,
        region: AudibleRegion,
    ): AppResult<MetadataContributorProfile?> =
        metadataService.getContributor(region, asin).map { it?.toMetadataContributorProfile() }

    override suspend fun refreshBookMetadata(
        asin: String,
        region: AudibleRegion,
    ): AppResult<MetadataBook?> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        return audible.getBook(asin, region, refresh = true).enrichWithItunesCover()
    }

    /**
     * Grafts iTunes' high-resolution cover onto a single-book metadata result.
     *
     * The metadata wizard's cover picker sources its "iTunes HD" option from
     * [MetadataBook.coverUrlMaxSize] — but the Audible mappers never populate it.
     * When the lookup succeeded with a book whose max-size cover is still empty,
     * fetch the best iTunes cover by title + primary author and copy its
     * max-resolution URL in. Failure-contained: a missing book, a blank title, a
     * null hit, a blank URL, or any iTunes error leaves the result untouched so
     * the Audible metadata still flows. One iTunes request per preview load.
     */
    private suspend fun AppResult<MetadataBook?>.enrichWithItunesCover(): AppResult<MetadataBook?> {
        val book = (this as? AppResult.Success)?.data ?: return this
        if (book.coverUrlMaxSize != null || book.title.isBlank()) return this
        val author =
            book.authors
                .firstOrNull()
                ?.name
                .orEmpty()
        val hit = (metadataService.findCover(book.title, author) as? AppResult.Success)?.data ?: return this
        val maxUrl = hit.maxSizeUrl.takeIf { it.isNotBlank() } ?: return this
        return AppResult.Success(book.copy(coverUrlMaxSize = maxUrl))
    }

    override suspend fun applyBookMetadata(
        bookId: BookId,
        asin: String,
        region: AudibleRegion,
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
            metadataProvider = audible,
            genreHierarchy = GenreHierarchyFromLadder(db, genreRepository, genreAutoCreator),
            ladderSource = { r, a ->
                when (val book = metadataService.getBook(r, a)) {
                    is AppResult.Success -> book.data?.genreLadders.orEmpty()
                    is AppResult.Failure -> emptyList()
                }
            },
        ).apply(bookId, asin, region, selection)
    }

    override suspend fun applyChapterNames(
        bookId: BookId,
        asin: String,
        region: AudibleRegion,
        ordinals: Set<Int>,
    ): AppResult<Unit> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        return ChapterNameApplier(
            bookRepository = bookRepository,
            metadataService = metadataService,
        ).apply(bookId, asin, region, ordinals)
    }

    override suspend fun applyContributorMetadata(
        contributorId: ContributorId,
        asin: String,
        region: AudibleRegion,
    ): AppResult<Unit> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        return ContributorMetadataApplier(
            contributorRepository = contributorRepository,
            imageStorage = imageDeps.imageStorage,
            metadataService = metadataService,
            imageHome = imageDeps.imageHome,
        ).apply(contributorId, asin, region)
    }

    override suspend fun searchCovers(
        bookId: BookId,
        region: AudibleRegion?,
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
            val relPath = "covers/${stored.path.fileName}"
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

private fun AudibleContributorProfile.toMetadataContributorProfile(): MetadataContributorProfile =
    MetadataContributorProfile(
        asin = asin,
        name = name,
        sortName = null,
        description = biography.takeIf { it.isNotBlank() },
        imageUrl = imageUrl.takeIf { it.isNotBlank() },
        birthDate = null,
        deathDate = null,
        website = null,
    )
