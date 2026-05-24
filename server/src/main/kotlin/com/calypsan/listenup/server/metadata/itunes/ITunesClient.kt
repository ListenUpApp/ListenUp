package com.calypsan.listenup.server.metadata.itunes

import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.result.AppResult
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Ktor-backed adapter for the iTunes Search API. Used **only** for cover art —
 * Audible is authoritative for metadata (titles, descriptions, contributors,
 * series, chapters). iTunes is used here because its artwork resolution is
 * typically higher than Audible's (up to 7000×7000, vs Audible's 500px cap).
 *
 * The URL transformation and matching heuristic are ported directly from
 * Go's implementation at `server/internal/metadata/itunes/cover.go` and
 * `search.go`. Key deviations from Go's full surface:
 * - No `SearchAudiobooks` — only [findCover] is exposed; chapter/metadata
 *   lookups are Audible's domain.
 * - No image-dimension probing — Go's `GetImageDimensions` is not ported
 *   because we request the maximum-size URL and trust iTunes to serve the
 *   actual maximum; probing would require an extra HTTP round-trip per cover.
 * - No rate limiter — the caller is responsible for pacing; the client itself
 *   is stateless so tests can inject a `MockEngine` without timing concerns.
 *
 * [CancellationException] is never swallowed — it is always rethrown.
 */
class ITunesClient(
    private val httpClient: HttpClient,
    private val json: Json,
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
        try {
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
                        val parsed = json.decodeFromString<ITunesSearchResponse>(response.bodyAsText())
                        val best = pickBest(parsed.results, title, author)
                        AppResult.Success(best?.let { toCoverHit(it) })
                    } catch (e: SerializationException) {
                        AppResult.Failure(MetadataError.Malformed(debugInfo = e.message))
                    } catch (e: IllegalArgumentException) {
                        // kotlinx.serialization throws IAE for type mismatches in some cases.
                        AppResult.Failure(MetadataError.Malformed(debugInfo = e.message))
                    }
                }

                HttpStatusCode.TooManyRequests -> {
                    AppResult.Failure(MetadataError.ExternalRateLimited())
                }

                else -> {
                    AppResult.Failure(
                        MetadataError.ExternalUnavailable(debugInfo = "HTTP ${response.status.value}"),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Failure(MetadataError.ExternalUnavailable(debugInfo = e.message))
        }

    /**
     * Pick the best match from [results].
     *
     * Strategy (ported from Go's `SearchAudiobooks` filter + ordering):
     * 1. Filter to real audiobook entries: `wrapperType == "audiobook"` OR
     *    `collectionType == "Audiobook"` (Go applies this filter after fetching).
     * 2. Among filtered candidates, prefer the first whose [ITunesSearchResult.collectionName]
     *    or [ITunesSearchResult.artistName] contains [title]/[author] respectively
     *    (case-insensitive substring match — equivalent to Go combining title+author
     *    into a single query and relying on iTunes relevance ordering).
     * 3. Fall back to the first filtered result (iTunes' own relevance ranking).
     * 4. If no audiobook-typed result exists at all, fall back to the first raw result.
     *
     * Go does not implement a separate fuzzy scorer; it relies on iTunes relevance
     * ordering. The substring check here is an improvement that avoids picking a
     * clearly wrong result when multiple candidates are returned.
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
     * Pattern: `Regex("/\\d+x\\d+bb\\.jpg$")`, identical to Go's `sizePattern`
     * in `cover.go`:
     *
     *     var sizePattern = regexp.MustCompile(`/\d+x\d+bb\.jpg$`)
     *     func MaxCoverURL(url string) string {
     *         return sizePattern.ReplaceAllString(url, "/"+MaxCoverSize)
     *     }
     *
     * [artworkUrl60] is used as a fallback when [artworkUrl100] is absent,
     * matching Go's selection logic (`artworkURL := r.ArtworkURL100; if artworkURL == "" { artworkURL = r.ArtworkURL60 }`).
     */
    private fun toCoverHit(result: ITunesSearchResult): ITunesCoverHit {
        val original =
            result.artworkUrl100?.ifEmpty { null }
                ?: result.artworkUrl60?.ifEmpty { null }
                ?: return ITunesCoverHit(coverUrl = "", maxSizeUrl = "")
        val maxSize = SIZE_PATTERN.replace(original, "/7000x7000bb.jpg")
        return ITunesCoverHit(coverUrl = original, maxSizeUrl = maxSize)
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
         *
         * Identical to Go's `sizePattern` in `cover.go`:
         *     regexp.MustCompile(`/\d+x\d+bb\.jpg$`)
         */
        val SIZE_PATTERN = Regex("""/\d+x\d+bb\.jpg$""")
    }
}
