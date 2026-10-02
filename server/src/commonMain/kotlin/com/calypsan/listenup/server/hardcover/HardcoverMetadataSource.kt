package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.server.metadata.spi.BookCoreMeta
import com.calypsan.listenup.server.metadata.spi.BookCoreSource
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.metadata.spi.ContributorHitMeta
import com.calypsan.listenup.server.metadata.spi.ContributorMeta
import com.calypsan.listenup.server.metadata.spi.ContributorSource
import com.calypsan.listenup.server.metadata.spi.GenreKind
import com.calypsan.listenup.server.metadata.spi.GenreMeta
import com.calypsan.listenup.server.metadata.spi.GenreSource
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.MoodSource
import com.calypsan.listenup.server.metadata.spi.SeriesMeta
import com.calypsan.listenup.server.metadata.spi.SeriesSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val MAX_MOODS = 6
private const val MIN_MOOD_VOTES = 3
private const val TOP_MOOD_SHARE_DIVISOR = 10
private const val MAX_GENRES = 5

/** The key a Hardcover author's profile is fetched by. */
const val HARDCOVER_AUTHOR_KEY_PREFIX = "hardcover:author:"

/** The selection key of a series only Hardcover knows (it rides `MetadataSeriesRef.asin`). */
const val HARDCOVER_SERIES_KEY_PREFIX = "hardcover:series:"

/**
 * Hardcover's community catalogue as a metadata source (#1542): it fills what Audible and Audnexus leave
 * empty — moods above all, then genres, and the series, description and author bio or photo where nothing
 * else has them. Routed last, so it only ever fills gaps (see [MetadataProviderId.gapFillers]).
 *
 * **Which book.** An existing link for [BookIdentity.bookId], anyone's, a hand-picked one first; else the
 * [matcher] — the ASIN, the book's ISBN, then one confident title match. No confident book means no
 * Hardcover fields: it never guesses between Hardcover's duplicates.
 *
 * **Cost.** One lookup and one details call per book, remembered in [cache], however many fields ask. Every
 * call reads through [catalogToken] (the admin's API token first) and the shared [rateLimiter].
 *
 * **Off.** With the admin's "Hardcover metadata" switch off, or no token able to read, it asks nothing and
 * contributes nothing.
 */
class HardcoverMetadataSource(
    private val graphQl: HardcoverGraphQlClient,
    private val catalogToken: HardcoverCatalogToken,
    private val matcher: HardcoverBookMatcher,
    private val rateLimiter: HardcoverRateLimiter,
    private val links: HardcoverBookLinkStore,
    private val identities: HardcoverBookIdentities,
    private val sourceSettings: HardcoverSourceSettings,
    private val cache: HardcoverMetadataCache = HardcoverMetadataCache(),
) : BookCoreSource,
    SeriesSource,
    GenreSource,
    MoodSource,
    ContributorSource {
    override val id: MetadataProviderId = MetadataProviderId.HARDCOVER

    /** One book's lookup at a time, so parallel field fetches for one match share a single lookup. */
    private val lookups = Mutex()

    override suspend fun getBookCore(
        book: BookIdentity,
        locale: MetadataLocale,
        refresh: Boolean,
    ): AppResult<BookCoreMeta?> = details(book, refresh).map { found -> found?.description?.let { BookCoreMeta(description = it) } }

    override suspend fun getSeries(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<List<SeriesMeta>?> =
        details(book).map { found ->
            found?.series?.map { SeriesMeta(key = HARDCOVER_SERIES_KEY_PREFIX + it.seriesId, title = it.name, sequence = it.sequence) }
                ?.ifEmpty { null }
        }

    override suspend fun getGenres(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<List<GenreMeta>?> =
        details(book).map { found ->
            found
                ?.genres
                ?.sortedByDescending { it.count }
                ?.take(MAX_GENRES)
                ?.map { GenreMeta(it.name, GenreKind.GENRE) }
                ?.ifEmpty { null }
        }

    override suspend fun getMoods(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<List<String>?> = details(book).map { found -> found?.let { wellSupportedMoods(it.moods) }?.ifEmpty { null } }

    override suspend fun searchContributors(
        name: String,
        locale: MetadataLocale,
    ): AppResult<List<ContributorHitMeta>> {
        if (name.isBlank() || !canRun()) return AppResult.Success(emptyList())
        val answer =
            catalogToken.read { token ->
                rateLimiter.await()
                graphQl.authorsNamed(token, name.trim())
            }
        return when (answer) {
            null -> AppResult.Success(emptyList())
            is HardcoverCall.Ok -> AppResult.Success(answer.value.map { ContributorHitMeta(HARDCOVER_AUTHOR_KEY_PREFIX + it.id, it.name) })
            else -> failure(answer)
        }
    }

    override suspend fun getContributor(
        key: String,
        locale: MetadataLocale,
        refresh: Boolean,
    ): AppResult<ContributorMeta?> {
        val authorId = key.takeIf { it.startsWith(HARDCOVER_AUTHOR_KEY_PREFIX) }?.removePrefix(HARDCOVER_AUTHOR_KEY_PREFIX)?.toLongOrNull()
        if (authorId == null || !canRun()) return AppResult.Success(null)
        val answer =
            catalogToken.read { token ->
                rateLimiter.await()
                graphQl.authorById(token, authorId)
            }
        return when (answer) {
            null -> AppResult.Success(null)
            is HardcoverCall.Ok -> AppResult.Success(answer.value?.let { ContributorMeta(key, it.name, it.bio, it.imageUrl) })
            else -> failure(answer)
        }
    }

    private suspend fun canRun(): Boolean = sourceSettings.metadataEnabled() && catalogToken.isAvailable()

    /** [book]'s Hardcover details, or `Success(null)` when the source is off or no book is confident. */
    private suspend fun details(
        book: BookIdentity,
        refresh: Boolean = false,
    ): AppResult<HardcoverBookDetails?> {
        if (!canRun()) return AppResult.Success(null)
        return lookups.withLock {
            val hcBookId =
                when (val resolved = resolve(book, refresh)) {
                    is Resolved.Found -> resolved.hcBookId
                    Resolved.NoBook -> return@withLock AppResult.Success(null)
                    is Resolved.Failed -> return@withLock failure(resolved.call)
                }
            if (!refresh) cache.details(hcBookId)?.let { return@withLock AppResult.Success(it) }
            val answer =
                catalogToken.read { token ->
                    rateLimiter.await()
                    graphQl.bookDetails(token, hcBookId)
                }
            when (answer) {
                null -> AppResult.Success(null)
                is HardcoverCall.Ok -> AppResult.Success(answer.value?.also { cache.rememberDetails(it) })
                else -> failure(answer)
            }
        }
    }

    private suspend fun resolve(
        book: BookIdentity,
        refresh: Boolean,
    ): Resolved {
        val key = "${book.bookId.orEmpty()}|${book.asin.orEmpty()}|${book.isbn.orEmpty()}"
        if (!refresh) {
            when (val known = cache.resolution(key)) {
                is HardcoverResolution.Book -> return Resolved.Found(known.hcBookId)
                HardcoverResolution.NoMatch -> return Resolved.NoBook
                null -> Unit
            }
        }
        book.bookId?.let { links.catalogBookFor(it) }?.let { linked ->
            cache.rememberResolution(key, HardcoverResolution.Book(linked))
            return Resolved.Found(linked)
        }
        val lookup = lookupIdentity(book)
        return when (val answer = catalogToken.read { token -> matcher.match(token, lookup) }) {
            null -> {
                Resolved.NoBook
            }

            is HardcoverCall.Ok -> {
                val resolution = answer.value?.let { HardcoverResolution.Book(it.hcBookId) } ?: HardcoverResolution.NoMatch
                cache.rememberResolution(key, resolution)
                if (resolution is HardcoverResolution.Book) Resolved.Found(resolution.hcBookId) else Resolved.NoBook
            }

            else -> {
                Resolved.Failed(answer)
            }
        }
    }

    /**
     * What the matcher looks the book up by: the picked edition's ASIN, else the book's own; the book's
     * ISBN; and its own title and primary author (the matcher needs both for a title match).
     */
    private suspend fun lookupIdentity(book: BookIdentity): BookIdentity {
        val local = book.bookId?.let { identities.identityOf(it) }
        val title = local?.title?.takeIf { it.isNotBlank() } ?: book.title
        return BookIdentity(
            asin = book.asin ?: local?.asin,
            isbn = book.isbn ?: local?.isbn,
            title = title,
            primaryAuthor = (local?.primaryAuthor ?: book.primaryAuthor).takeIf { title.isNotBlank() },
        )
    }

    private fun failure(call: HardcoverCall<*>): AppResult.Failure =
        AppResult.Failure(HardcoverError.Unavailable(debugInfo = "hardcover metadata: ${call.describe()}"))

    private fun HardcoverCall<*>.describe(): String =
        when (this) {
            is HardcoverCall.Ok -> "ok"
            HardcoverCall.Unauthorized -> "token rejected"
            is HardcoverCall.MissingScope -> "missing scope ${scope.orEmpty()}"
            is HardcoverCall.Throttled -> "throttled"
            is HardcoverCall.Failed -> detail
        }

    /** One resolution's outcome for [details]: the book, none, or a failure to report. */
    private sealed interface Resolved {
        data class Found(
            val hcBookId: Long,
        ) : Resolved

        data object NoBook : Resolved

        data class Failed(
            val call: HardcoverCall<*>,
        ) : Resolved
    }
}

/**
 * The well-supported moods (#1542, deviation 16): each has at least [MIN_MOOD_VOTES] votes **and** at least a
 * tenth of the top mood's, most voted first, at most [MAX_MOODS], each capitalised ("adventurous" → "Adventurous").
 */
internal fun wellSupportedMoods(moods: List<HardcoverTag>): List<String> {
    val top = moods.maxOfOrNull { it.count } ?: return emptyList()
    return moods
        .filter { it.count >= MIN_MOOD_VOTES && it.count * TOP_MOOD_SHARE_DIVISOR >= top }
        .sortedByDescending { it.count }
        .take(MAX_MOODS)
        .map { mood -> mood.name.replaceFirstChar { it.uppercaseChar() } }
}
