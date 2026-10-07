package com.calypsan.listenup.server.matching.review

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.BookCandidateKey
import com.calypsan.listenup.api.dto.match.BookMatchReview
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.dto.match.HandEdit
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.FieldSourceKind
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.server.metadata.ComposedOptions
import com.calypsan.listenup.server.metadata.CoreFailure
import com.calypsan.listenup.server.metadata.EnrichmentCoordinator
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.presentedAs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Each source gets this long to answer while a Review composes; a slower one is "didn't answer in time". */
internal val REVIEW_DEADLINE: Duration = 8.seconds

/** A live mood on the book: its id and name. */
internal data class BookMood(
    val id: String,
    val name: String,
)

/**
 * A Review, with everything Apply needs to turn the person's choices into writes: each option's write value,
 * each tile's provider, each suggestion's providers, and the chapter names. Built the same way for Review and
 * for Apply, so Apply writes exactly what was reviewed.
 */
internal data class BookReviewModel(
    val book: BookSyncPayload,
    val key: BookCandidateKey,
    val locale: MetadataLocale,
    val identity: BookIdentity,
    val review: BookMatchReview,
    val fields: List<ReviewedField>,
    val covers: List<ReviewedCover>,
    val genres: List<ReviewedLabel>,
    val moods: List<ReviewedLabel>,
    val currentMoods: List<BookMood>,
    val chapters: ReviewedChapters?,
)

/**
 * Composes a Review for one Find candidate (spec, *Review: per-field candidates*). Each provider in the
 * candidate's key is read by its own ref; gap fillers join as they always have. The candidate counts as found
 * when any of its own sources answered with something. When none did, the failure is typed: a rate limit, else a
 * timeout, else unavailable when they all failed, else not found.
 */
internal class BookReviewer(
    private val coordinator: EnrichmentCoordinator,
    private val probe: suspend (String) -> Pair<Int, Int>?,
    private val genreIdentity: (BookSyncPayload) -> LabelIdentity,
    private val currentMoods: suspend (String) -> List<BookMood>,
    private val displayName: suspend (String) -> String?,
    private val deadline: Duration = REVIEW_DEADLINE,
) {
    suspend fun review(
        book: BookSyncPayload,
        key: BookCandidateKey,
        locale: MetadataLocale,
    ): AppResult<BookReviewModel> {
        val identity = identityFor(book, key.refs)
        val options = coordinator.composeOptions(identity, locale, deadline = deadline)
        val keyProviders = key.refs.map { it.provider }.toSet()
        if (!foundIn(options, keyProviders)) return AppResult.Failure(failureFor(options, keyProviders))

        val routes = coordinator.routes
        val fields = FieldReviewer.review(book, options, routes) { handEdit(it.by, it.at) }.let { resolveNames(it) }
        val cover = CoverReviewer.review(book, options.covers, routes, keyProviders, probe)
        val moods = currentMoods(book.id)
        val (genreReview, genres) =
            LabelReviewer.review(
                yours = book.genres.map { it.name },
                byProvider = options.genres.mapValues { (_, list) -> list.map { it.name } },
                order = routes.orderFor(BookField.GENRES),
                identity = genreIdentity(book),
            )
        val (moodReview, moodSuggestions) =
            LabelReviewer.review(
                yours = moods.map { it.name },
                byProvider = options.moods,
                order = routes.orderFor(BookField.MOODS),
                identity = MoodLabelIdentity,
            )
        val (chapterReview, chapters) =
            ChapterNamesReviewer.review(book.chapters, coordinator.composeChaptersWithSource(identity, locale))
        return AppResult.Success(
            BookReviewModel(
                book = book,
                key = key,
                locale = locale,
                identity = identity,
                review =
                    BookMatchReview(
                        candidate = key,
                        region = locale,
                        basedOnRevision = book.revision,
                        fields = fields.map { it.review },
                        cover = cover.review,
                        genres = genreReview,
                        moods = moodReview,
                        chapterNames = chapterReview,
                    ),
                fields = fields,
                covers = cover.covers,
                genres = genres,
                moods = moodSuggestions,
                currentMoods = moods,
                chapters = chapters,
            ),
        )
    }

    /** Hand edits name their editor; the names are looked up once per Review. */
    private suspend fun resolveNames(fields: List<ReviewedField>): List<ReviewedField> {
        val names =
            fields
                .mapNotNull { it.review.handEdit?.byUserId }
                .distinct()
                .associateWith { displayName(it)?.takeIf(String::isNotBlank) }
        return fields.map { field ->
            val edit = field.review.handEdit ?: return@map field
            field.copy(review = field.review.copy(handEdit = edit.copy(byName = edit.byUserId?.let(names::get))))
        }
    }

    private fun handEdit(
        by: String?,
        at: Long,
    ): HandEdit = HandEdit(byUserId = by, byName = null, at = at.takeIf { it > 0 })

    private fun foundIn(
        options: ComposedOptions,
        keyProviders: Set<String>,
    ): Boolean {
        fun MetadataProviderId.inKey() = presentedAs().value in keyProviders

        fun Map<MetadataProviderId, List<*>>.answered() = any { (id, list) -> id.inKey() && list.isNotEmpty() }
        return options.cores.keys.any { it.inKey() } ||
            options.covers.answered() ||
            options.genres.answered() ||
            options.series.answered() ||
            options.moods.answered()
    }

    private fun failureFor(
        options: ComposedOptions,
        keyProviders: Set<String>,
    ): AppError {
        val failures = options.coreFailures.filterKeys { it.presentedAs().value in keyProviders }.values
        val asked = options.coreAsked.filter { it.presentedAs().value in keyProviders }
        val rateLimited = failures.filterIsInstance<CoreFailure.RateLimited>()
        return when {
            rateLimited.isNotEmpty() -> {
                MetadataError.ExternalRateLimited(
                    debugInfo = "review: a candidate source is rate-limited",
                    retryAfterSeconds = rateLimited.mapNotNull { it.retryAfterSeconds }.maxOrNull(),
                )
            }

            failures.any { it == CoreFailure.TimedOut } -> {
                MetadataError.ExternalTimeout(debugInfo = "review: a candidate source didn't answer in time")
            }

            asked.isNotEmpty() && failures.size == asked.size -> {
                MetadataError.ExternalUnavailable(debugInfo = "review: every candidate source failed")
            }

            else -> {
                MetadataError.NotFound(debugInfo = "review: no candidate source has this edition")
            }
        }
    }

    companion object {
        /**
         * What every provider is asked for a Review: the candidate's refs (the `audible` one as the ASIN), and the
         * book's own title, author and id for the gap fillers.
         */
        fun identityFor(
            book: BookSyncPayload,
            refs: List<ExternalRef>,
        ): BookIdentity =
            BookIdentity(
                asin = refs.firstOrNull { it.provider == ExternalRef.AUDIBLE }?.id,
                title = book.title,
                primaryAuthor =
                    book.contributors
                        .firstOrNull { ContributorRole.fromApiValue(it.role) == ContributorRole.AUTHOR }
                        ?.name,
                durationMs = book.totalDuration.takeIf { it > 0 },
                bookId = book.id,
                refs = refs,
            )
    }
}

/** Whether [field] was set by hand — the fields Review protects by default. */
internal fun BookSyncPayload.isHandEdited(field: BookField): Boolean =
    fieldProvenance[field]?.kind == FieldSourceKind.USER
