package com.calypsan.listenup.client.presentation.genredestination

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.FacetStats
import com.calypsan.listenup.api.result.getOrElse
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.GenreRepository
import com.calypsan.listenup.core.GenreId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val logger = KotlinLogging.logger {}
private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

/**
 * ViewModel for a genre destination page — the standalone landing screen for a single genre,
 * combining its curated identity (name, blurb, icon, accent hue) with its position in the genre
 * hierarchy (breadcrumb + direct sub-genres) and its book set.
 *
 * The genre tree comes from the reactive, Room-backed [GenreRepository.observeAll] — used to
 * resolve the target genre and derive its breadcrumb and direct sub-genres via materialized-path
 * prefix matching (the same derivation
 * [com.calypsan.listenup.client.presentation.admin.AdminCategoriesViewModel] uses to build its
 * tree). Stats and the book set are RPC-fetched via [GenreRepository.getGenreStats] /
 * [GenreRepository.browseBooks] — the server aggregate is authoritative for total length, so it
 * is never summed client-side — whenever the target genre or the `includeSubGenres` scope
 * changes. The returned book ids are then hydrated reactively through
 * [BookRepository.observeBookListItems], so the grid tracks Room updates without a manual
 * refresh (mirrors [com.calypsan.listenup.client.presentation.browsefacet.BrowseFacetViewModel]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GenreDestinationViewModel(
    private val genreRepository: GenreRepository,
    private val bookRepository: BookRepository,
) : ViewModel() {
    private val request = MutableStateFlow<Request?>(null)

    val state: StateFlow<GenreDestinationUiState> =
        combine(genreRepository.observeAll(), request) { genres, req -> genres to req }
            .flatMapLatest { (genres, req) ->
                if (req == null) {
                    flowOf(GenreDestinationUiState.Loading)
                } else {
                    resolveDestination(genres, req)
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                initialValue = GenreDestinationUiState.Loading,
            )

    /** Load (or switch to) the destination page for [genreId]. Resets any scope toggle. */
    fun load(genreId: GenreId) {
        request.value = Request(genreId)
    }

    /**
     * Widens/narrows the book scope between the genre's direct books and its whole subtree.
     * No-op on a leaf genre (no sub-genres to widen into) and before a genre has loaded.
     */
    fun toggleIncludeSubGenres() {
        val ready = state.value as? GenreDestinationUiState.Ready ?: return
        if (!ready.hasSubs) return
        val current = request.value ?: return
        request.value = current.copy(includeOverride = !ready.includeSubGenres)
    }

    private fun resolveDestination(
        genres: List<Genre>,
        req: Request,
    ): Flow<GenreDestinationUiState> =
        flow {
            val target = genres.find { it.id == req.genreId.value }
            if (target == null) {
                emit(GenreDestinationUiState.NotFound)
                return@flow
            }

            val children =
                genres
                    .filter { it.path.substringBeforeLast('/', "") == target.path }
                    .sortedBy { it.name }
            val hasSubs = children.isNotEmpty()
            val includeSubGenres = hasSubs && req.includeOverride ?: true

            val identity =
                GenreIdentity(
                    name = target.name,
                    slug = target.slug,
                    // Genre (the synced domain/Room model) carries no `description` field, so a
                    // curator blurb is not available client-side. Left null rather than inventing
                    // sync plumbing to fetch one — see task escalation note.
                    blurb = null,
                    icon = FacetIdentity.icon(target.name),
                    hue = FacetIdentity.hue(target.name),
                )
            val breadcrumb = ancestorsOf(target, genres)
            val subGenres =
                children.map {
                    SubGenre(genreId = GenreId(it.id), name = it.name, bookCount = it.bookCount)
                }

            val stats =
                genreRepository.getGenreStats(req.genreId, includeSubGenres).getOrElse { error ->
                    logger.warn { "Failed to load genre stats for ${req.genreId}: ${error.message}" }
                    FacetStats.EMPTY
                }
            val bookIds =
                genreRepository.browseBooks(req.genreId, includeSubGenres).getOrElse { error ->
                    logger.warn { "Failed to load books for genre ${req.genreId}: ${error.message}" }
                    emptyList()
                }

            emitAll(
                bookRepository.observeBookListItems(bookIds.map { it.value }).map { books ->
                    GenreDestinationUiState.Ready(
                        identity = identity,
                        breadcrumb = breadcrumb,
                        subGenres = subGenres,
                        hasSubs = hasSubs,
                        includeSubGenres = includeSubGenres,
                        stats = stats,
                        books = books,
                    )
                },
            )
        }

    /** Ancestors of [target], root-first, excluding [target] itself — derived from its materialized path. */
    private fun ancestorsOf(
        target: Genre,
        genres: List<Genre>,
    ): List<GenreCrumb> {
        val segments = target.path.trim('/').split('/')
        return (1 until segments.size)
            .map { depth -> "/" + segments.take(depth).joinToString("/") }
            .mapNotNull { path -> genres.find { it.path == path } }
            .map { GenreCrumb(genreId = GenreId(it.id), name = it.name) }
    }

    /** The genre currently targeted, plus an optional user toggle override for the sub-genre scope. */
    private data class Request(
        val genreId: GenreId,
        val includeOverride: Boolean? = null,
    )
}

/** Sealed UiState for a genre destination page. */
sealed interface GenreDestinationUiState {
    /** Upstream has not yet resolved the requested genre. */
    data object Loading : GenreDestinationUiState

    /** The genre resolved; carries its identity, hierarchy context, and current book scope. */
    data class Ready(
        val identity: GenreIdentity,
        val breadcrumb: List<GenreCrumb>,
        val subGenres: List<SubGenre>,
        val hasSubs: Boolean,
        val includeSubGenres: Boolean,
        val stats: FacetStats,
        val books: List<BookListItem>,
    ) : GenreDestinationUiState

    /** The requested genre id resolved to no live row (absent or tombstoned). */
    data object NotFound : GenreDestinationUiState
}

/** Curated visual identity for a genre destination page — name, blurb, icon, and accent hue. */
data class GenreIdentity(
    val name: String,
    val slug: String,
    /** Curator blurb, when available. Null when the domain model carries no description. */
    val blurb: String?,
    val icon: FacetIcon,
    val hue: String,
)

/** One ancestor step in a genre destination page's breadcrumb trail, root-first. */
data class GenreCrumb(
    val genreId: GenreId,
    val name: String,
)

/** A direct child genre, as shown in a genre destination page's sub-genre list. */
data class SubGenre(
    val genreId: GenreId,
    val name: String,
    val bookCount: Int,
)
