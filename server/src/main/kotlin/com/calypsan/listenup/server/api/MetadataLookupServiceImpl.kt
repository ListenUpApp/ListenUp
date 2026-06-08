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
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.metadata.ImageStorage
import com.calypsan.listenup.server.metadata.provider.MetadataProvider
import com.calypsan.listenup.server.metadata.provider.MetadataSource
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.CoverSearchService
import com.calypsan.listenup.server.services.MetadataService
import com.calypsan.listenup.server.services.SeriesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.io.files.Path

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
    private val imageStorage: ImageStorage,
    private val coverImageStore: CoverImageStore,
    private val imageHome: Path,
    private val permissionPolicy: UserPermissionPolicy,
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
            imageStorage = imageStorage,
            coverImageStore = coverImageStore,
            imageHome = imageHome,
            permissionPolicy = permissionPolicy,
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
    ): AppResult<MetadataBook?> = audible.getBook(asin, region)

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
        return audible.getBook(asin, region, refresh = true)
    }

    override suspend fun applyBookMetadata(
        bookId: BookId,
        asin: String,
        region: AudibleRegion,
        selection: MetadataApplySelection,
    ): AppResult<Unit> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        return BookMetadataApplier(
            bookRepository = bookRepository,
            contributorRepository = contributorRepository,
            seriesRepository = seriesRepository,
            imageStorage = imageStorage,
            coverImageStore = coverImageStore,
            metadataProvider = audible,
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
            imageStorage = imageStorage,
            metadataService = metadataService,
            imageHome = imageHome,
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
            val bytes = imageStorage.downloadBytes(url)
            val stored = coverImageStore.store.store(bookId.value, bytes, "image/jpeg")
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
