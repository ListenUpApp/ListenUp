package com.calypsan.listenup.server.metadata.itunes

import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.server.logging.loggerFor
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val log = loggerFor<ITunesClient>()

/**
 * Ktor-backed adapter for the iTunes Search API. Used **only** for cover art —
 * Audible is authoritative for metadata (titles, descriptions, contributors,
 * series, chapters). iTunes is used here because its artwork resolution is
 * typically higher than Audible's (up to 7000×7000, vs Audible's 500px cap).
 *
 * Key scope limits of this iTunes client:
 * - No `SearchAudiobooks` — only [findCover] is exposed; chapter/metadata
 *   lookups are Audible's domain.
 * - No image-dimension probing — we request the maximum-size URL and trust
 *   iTunes to serve the actual maximum; probing would require an extra HTTP
 *   round-trip per cover.
 * - No rate limiter — the caller is responsible for pacing; the client itself
 *   is stateless so tests can inject a `MockEngine` without timing concerns.
 *
 * [CancellationException] is never swallowed — it is always rethrown.
 */
class ITunesClient(
    private val httpClient: HttpClient,
    private val json: Json,
    private val rateLimiter: ITunesRateLimiter = ITunesRateLimiter(),
) : ITunesApi {
    /**
     * Searches iTunes for the best cover matching [title] + [author].
     *
     * Returns [AppResult.Success] with:
     * - A non-null [ITunesCoverHit] when a matching audiobook is found.
     * - `null` when no results are returned by iTunes.
     *
     * The [ITunesCoverHit.maxSizeUrl] is the high-resolution variant (up to
     * 7000×7000); use it for cover downloads. [ITunesCoverHit.coverUrl] is the
     * original 100×100 thumbnail, kept for low-bandwidth contexts.
     *
     * Error mapping mirrors [AudibleClient]:
     * - 429 → [MetadataError.ExternalRateLimited]
     * - 5xx or network failure → [MetadataError.ExternalUnavailable]
     * - unparseable response → [MetadataError.Malformed]
     */
    override suspend fun findCover(
        title: String,
        author: String,
    ): AppResult<ITunesCoverHit?> =
        fetchResults(title, author).map { results ->
            pickBest(results, title, author)?.let { toCoverHit(it) }
        }

    /**
     * Searches iTunes for all audiobook cover candidates matching [title] + [author],
     * each mapped to its max-resolution variant and stamped with its collectionId.
     */
    override suspend fun searchCovers(
        title: String,
        author: String,
    ): AppResult<List<ITunesCoverHit>> =
        fetchResults(title, author).map { results ->
            results
                .filter { r -> r.wrapperType == AUDIOBOOK_MEDIA_TYPE || r.collectionType == AUDIOBOOK_COLLECTION_TYPE }
                .ifEmpty { results }
                .mapNotNull { toCoverHit(it).takeIf { hit -> hit.coverUrl.isNotEmpty() } }
        }

    /**
     * Single network + error-mapping path shared by [findCover] and [searchCovers]:
     * paces via [rateLimiter], fires the audiobook search, and maps the response to
     * the raw [ITunesSearchResult] list or a typed failure. Callers project the list.
     *
     * Error mapping (mirrors [AudibleClient]):
     * - 429 → [MetadataError.ExternalRateLimited]
     * - 5xx / network failure → [MetadataError.ExternalUnavailable]
     * - unparseable response → [MetadataError.Malformed]
     *
     * [CancellationException] is always rethrown.
     */
    private suspend fun fetchResults(
        title: String,
        author: String,
    ): AppResult<List<ITunesSearchResult>> =
        try {
            log.debug { "iTunes cover search: title='$title' author='$author'" }
            rateLimiter.await()
            val response =
                httpClient.get(SEARCH_BASE_URL) {
                    parameter("term", "$title $author")
                    parameter("media", AUDIOBOOK_MEDIA_TYPE)
                    parameter("entity", AUDIOBOOK_MEDIA_TYPE)
                    parameter("limit", DEFAULT_LIMIT)
                }
            when (response.status) {
                HttpStatusCode.OK -> {
                    try {
                        val results = json.decodeFromString<ITunesSearchResponse>(response.bodyAsText()).results
                        log.debug { "iTunes cover search result: matches=${results.size}" }
                        AppResult.Success(results)
                    } catch (e: SerializationException) {
                        AppResult.Failure(MetadataError.Malformed(debugInfo = e.message))
                    } catch (e: IllegalArgumentException) {
                        // kotlinx.serialization throws IAE for type mismatches in some cases.
                        AppResult.Failure(MetadataError.Malformed(debugInfo = e.message))
                    }
                }

                HttpStatusCode.TooManyRequests -> {
                    log.warn { "iTunes cover search rate-limited: title='$title'" }
                    AppResult.Failure(MetadataError.ExternalRateLimited())
                }

                else -> {
                    log.warn { "iTunes cover search failed: title='$title' status=${response.status.value}" }
                    AppResult.Failure(
                        MetadataError.ExternalUnavailable(debugInfo = "HTTP ${response.status.value}"),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "iTunes cover search network failure: title='$title'" }
            AppResult.Failure(MetadataError.ExternalUnavailable(debugInfo = e.message))
        }

    /**
     * Pick the best match from [results].
     *
     * Strategy:
     * 1. Filter to real audiobook entries: `wrapperType == "audiobook"` OR
     *    `collectionType == "Audiobook"` (applied after fetching).
     * 2. Among filtered candidates, prefer the first whose [ITunesSearchResult.collectionName]
     *    or [ITunesSearchResult.artistName] contains [title]/[author] respectively
     *    (case-insensitive substring match, relying on iTunes relevance ordering).
     * 3. Fall back to the first filtered result (iTunes' own relevance ranking).
     * 4. If no audiobook-typed result exists at all, fall back to the first raw result.
     *
     * The substring check is a deliberate addition on top of iTunes' relevance
     * ordering: it avoids picking a clearly wrong result when multiple candidates
     * are returned.
     */
    private fun pickBest(
        results: List<ITunesSearchResult>,
        title: String,
        author: String,
    ): ITunesSearchResult? {
        val audiobooks =
            results.filter { r ->
                r.wrapperType == AUDIOBOOK_MEDIA_TYPE || r.collectionType == AUDIOBOOK_COLLECTION_TYPE
            }
        val candidates = audiobooks.ifEmpty { results }

        return candidates.firstOrNull { r ->
            val titleMatch =
                r.collectionName?.contains(title, ignoreCase = true) == true
            val authorMatch =
                r.artistName?.contains(author, ignoreCase = true) == true
            titleMatch && authorMatch
        } ?: candidates.firstOrNull()
    }

    /**
     * Build an [ITunesCoverHit] from [result].
     *
     * iTunes artwork URLs contain a size fragment like `/100x100bb.jpg` or
     * `/60x60bb.jpg`. We replace it with `/7000x7000bb.jpg` — iTunes then
     * serves the actual maximum available size (typically 2400×2400 or 3000×3000;
     * requesting 7000×7000 never overshoots, it just gets the max).
     *
     * Pattern: `Regex("/\\d+x\\d+bb\\.jpg$")` — replace the matched size segment
     * with the maximum-size segment.
     *
     * [artworkUrl60] is used as a fallback when [artworkUrl100] is absent.
     */
    private fun toCoverHit(result: ITunesSearchResult): ITunesCoverHit {
        val sourceId = result.collectionId?.toString() ?: ""
        val original =
            result.artworkUrl100?.ifEmpty { null }
                ?: result.artworkUrl60?.ifEmpty { null }
                ?: return ITunesCoverHit(coverUrl = "", maxSizeUrl = "", sourceId = sourceId)
        val maxSize = SIZE_PATTERN.replace(original, "/7000x7000bb.jpg")
        return ITunesCoverHit(coverUrl = original, maxSizeUrl = maxSize, sourceId = sourceId)
    }

    private companion object {
        const val SEARCH_BASE_URL = "https://itunes.apple.com/search"
        const val DEFAULT_LIMIT = 10
        const val AUDIOBOOK_MEDIA_TYPE = "audiobook"
        const val AUDIOBOOK_COLLECTION_TYPE = "Audiobook"

        /**
         * Matches iTunes artwork size fragments like `/100x100bb.jpg`, `/60x60bb.jpg`,
         * `/200x200bb.jpg`, etc. Anchored to end-of-string so partial paths are not
         * accidentally replaced.
         */
        val SIZE_PATTERN = Regex("""/\d+x\d+bb\.jpg$""")
    }
}
