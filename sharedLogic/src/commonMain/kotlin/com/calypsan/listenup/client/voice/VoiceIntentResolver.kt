package com.calypsan.listenup.client.voice

import com.calypsan.listenup.api.result.getOrNull
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.HomeRepository
import com.calypsan.listenup.client.domain.repository.SearchRepository
import com.calypsan.listenup.client.domain.repository.SeriesRepository

/**
 * Resolves voice queries to playback intents.
 *
 * Resolution priority:
 * 1. Resume phrases ("continue", "my audiobook")
 * 2. Series navigation ("next book", "book 2")
 * 3. Hint-based search (structured data from assistant)
 * 4. Freeform search (raw query to SearchRepository)
 */
class VoiceIntentResolver(
    private val searchRepository: SearchRepository,
    private val homeRepository: HomeRepository,
    private val seriesRepository: SeriesRepository,
    private val bookRepository: BookRepository,
) {
    companion object {
        // Search configuration
        private const val MAX_SEARCH_RESULTS = 10

        // Confidence thresholds for intent resolution
        private const val HIGH_CONFIDENCE_THRESHOLD = 0.8f
        private const val AMBIGUOUS_THRESHOLD = 0.5f

        // Base confidence from search engine score
        // Search scores are normalized to this range to leave room for boosts
        private const val SEARCH_SCORE_WEIGHT = 0.5f
        private const val MAX_BASE_CONFIDENCE = 0.5f

        // Confidence boosts for title matching
        private const val EXACT_TITLE_MATCH_BOOST = 0.5f
        private const val TITLE_STARTS_WITH_BOOST = 0.3f
        private const val TITLE_CONTAINS_QUERY_BOOST = 0.2f

        // Confidence boosts for assistant hints
        private const val TITLE_HINT_MATCH_BOOST = 0.3f
        private const val AUTHOR_HINT_MATCH_BOOST = 0.2f
    }

    /**
     * Resolve a voice query to a playback intent.
     *
     * @param query The raw voice query text
     * @param hints Structured hints from the voice assistant (optional)
     * @return The resolved playback intent
     */
    suspend fun resolve(
        query: String,
        hints: VoiceHints = VoiceHints(),
    ): PlaybackIntent {
        // Priority 1: Check for resume phrases
        if (ResumePhraseDetector.isResumeIntent(query)) {
            return PlaybackIntent.Resume
        }

        // Priority 2: Check for series navigation
        val seriesNav = SeriesNavigationDetector.detect(query)
        if (seriesNav !is SeriesNavigation.NotSeriesNavigation) {
            val seriesIntent = resolveSeriesNavigation(seriesNav)
            if (seriesIntent != null) {
                return seriesIntent
            }
            // Fall through to search if no series context
        }

        // Priority 3 & 4: Search-based resolution
        return resolveViaSearch(query, hints)
    }

    private suspend fun resolveViaSearch(
        query: String,
        hints: VoiceHints,
    ): PlaybackIntent {
        val searchResult =
            searchRepository.search(
                query = query,
                types = listOf(SearchHitType.BOOK),
                limit = MAX_SEARCH_RESULTS,
            )

        if (searchResult.hits.isEmpty()) {
            return PlaybackIntent.NotFound(query)
        }

        // Score and rank results
        val scoredMatches =
            searchResult.hits
                .filter { it.type == SearchHitType.BOOK }
                .map { hit -> scoreMatch(hit, query, hints) }
                .sortedByDescending { it.confidence }

        if (scoredMatches.isEmpty()) {
            return PlaybackIntent.NotFound(query)
        }

        val topMatch = scoredMatches.first()

        // High confidence single match - auto-play
        if (topMatch.confidence >= HIGH_CONFIDENCE_THRESHOLD) {
            return PlaybackIntent.PlayBook(topMatch.bookId)
        }

        // Multiple viable matches - return ambiguous with best guess
        val viableMatches = scoredMatches.filter { it.confidence >= AMBIGUOUS_THRESHOLD }
        return if (viableMatches.size > 1) {
            PlaybackIntent.Ambiguous(
                candidates = viableMatches,
                bestGuess = topMatch.takeIf { it.confidence >= AMBIGUOUS_THRESHOLD },
            )
        } else if (viableMatches.size == 1) {
            // Single match above ambiguous threshold but below high confidence
            // Still play it - it's the only viable option
            PlaybackIntent.PlayBook(viableMatches.first().bookId)
        } else {
            PlaybackIntent.NotFound(query)
        }
    }

    private fun scoreMatch(
        hit: SearchHit,
        query: String,
        hints: VoiceHints,
    ): ResolvedMatch {
        val normalizedQuery = query.lowercase().trim()
        val normalizedTitle = hit.name.lowercase()

        // Start with search engine score (normalized to 0.0-MAX_BASE_CONFIDENCE range)
        // This leaves room for boosts to push confidence above thresholds
        var confidence = (hit.score * SEARCH_SCORE_WEIGHT).coerceIn(0f, MAX_BASE_CONFIDENCE)

        // Exact title match boost
        if (normalizedTitle == normalizedQuery) {
            confidence += EXACT_TITLE_MATCH_BOOST
        } else if (normalizedTitle.startsWith(normalizedQuery)) {
            confidence += TITLE_STARTS_WITH_BOOST
        } else if (normalizedTitle.contains(normalizedQuery)) {
            confidence += TITLE_CONTAINS_QUERY_BOOST
        }

        // Title hint match boost
        hints.title?.let { titleHint ->
            if (normalizedTitle.equals(titleHint.lowercase(), ignoreCase = true)) {
                confidence += TITLE_HINT_MATCH_BOOST
            }
        }

        // Author hint match boost
        hints.artist?.let { artistHint ->
            hit.author?.let { author ->
                if (author.lowercase().contains(artistHint.lowercase())) {
                    confidence += AUTHOR_HINT_MATCH_BOOST
                }
            }
        }

        return ResolvedMatch(
            bookId = hit.id,
            title = hit.name,
            authorNames = hit.author,
            confidence = confidence.coerceIn(0f, 1f),
        )
    }

    private suspend fun resolveSeriesNavigation(navigation: SeriesNavigation): PlaybackIntent? {
        // Get current series context from most recent book
        val context = getCurrentSeriesContext() ?: return null

        val bookIds = seriesRepository.getBookIdsForSeries(context.seriesId)
        if (bookIds.isEmpty()) return null

        // Batch-load all books in a single query (avoids N+1 problem)
        val books = bookRepository.getBookListItems(bookIds)

        // Map books to their sequence numbers and sort
        val booksWithSequence =
            books
                .map { book ->
                    val sequence =
                        book.series
                            .find { it.seriesId == context.seriesId }
                            ?.sequence
                    book to sequence
                }.sortedBy { (_, sequence) ->
                    sequence?.toFloatOrNull() ?: Float.MAX_VALUE
                }

        val targetBookId =
            when (navigation) {
                is SeriesNavigation.Next -> {
                    // Find the book after current
                    val currentIndex =
                        booksWithSequence.indexOfFirst { (book, _) ->
                            book.id.value == context.currentBookId
                        }
                    if (currentIndex >= 0 && currentIndex < booksWithSequence.size - 1) {
                        booksWithSequence[currentIndex + 1].first.id.value
                    } else {
                        null
                    }
                }

                is SeriesNavigation.First -> {
                    booksWithSequence
                        .firstOrNull()
                        ?.first
                        ?.id
                        ?.value
                }

                is SeriesNavigation.BySequence -> {
                    // Find book by sequence number
                    booksWithSequence
                        .find { (_, sequence) ->
                            sequence == navigation.sequence
                        }?.first
                        ?.id
                        ?.value
                }

                is SeriesNavigation.NotSeriesNavigation -> {
                    null
                }
            }

        return targetBookId?.let {
            PlaybackIntent.PlaySeriesFrom(
                seriesId = context.seriesId,
                startBookId = it,
            )
        }
    }

    private suspend fun getCurrentSeriesContext(): SeriesContext? {
        val recentBooks = homeRepository.getContinueListening(1).getOrNull()
        val currentBook = recentBooks?.firstOrNull() ?: return null

        val book = bookRepository.getBookListItem(currentBook.bookId) ?: return null
        val primarySeries = book.series.firstOrNull() ?: return null

        return SeriesContext(
            seriesId = primarySeries.seriesId,
            currentBookId = book.id.value,
            currentSequence = primarySeries.sequence,
        )
    }
}

/**
 * Context about the user's current series position.
 */
private data class SeriesContext(
    val seriesId: String,
    val currentBookId: String,
    val currentSequence: String?,
)
