package com.calypsan.listenup.server.matching

import com.calypsan.listenup.api.dto.match.BookCandidate
import com.calypsan.listenup.api.dto.match.BookCandidateKey
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.dto.match.FoundIn
import com.calypsan.listenup.api.dto.match.MatchReason
import com.calypsan.listenup.api.dto.match.MatchTier
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.server.metadata.spi.BookMatch
import com.calypsan.listenup.server.metadata.spi.MatchScorer
import com.calypsan.listenup.server.metadata.spi.presentedAs
import com.calypsan.listenup.server.metadata.spi.toMetadataSource
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Turns merged hits into ranked candidates (spec, *Find*, step 5). Each is scored against your copy by
 * [MatchScorer]; [STRONG_SCORE] and above is Strong — but only with a length on both sides to compare —
 * anything else Maybe, and nothing is hidden. Order is tier,
 * then score — stable, so ties keep source order. The best match is the first Strong; a current link is
 * labelled, never pinned.
 */
internal object CandidateRanker {
    /** The score at or above which a candidate is a Strong match. */
    const val STRONG_SCORE: Double = 0.8

    private const val SAME_LENGTH_MS: Long = 30_000L
    private const val NEAR_LENGTH_MS: Long = 5 * 60_000L
    private const val MS_PER_MINUTE: Double = 60_000.0
    private const val YEAR_DIGITS = 4

    fun rank(
        subject: FindSubject,
        searchRegion: String,
        groups: List<MergedHits>,
    ): List<BookCandidate> {
        val ordered =
            groups
                .map { it.toCandidate(subject, searchRegion) }
                .sortedWith(compareBy<BookCandidate> { it.tier.ordinal }.thenByDescending { it.score })
        val best = ordered.firstOrNull { it.tier == MatchTier.STRONG }
        return ordered.map { if (it === best) it.copy(isBest = true) else it }
    }

    private fun MergedHits.toCandidate(
        subject: FindSubject,
        searchRegion: String,
    ): BookCandidate {
        val books = members.map { it.book }
        val everyone = members + attached
        val title = books.first().title
        val authors = books.firstNotNullOfOrNull { book -> book.authors.takeIf { it.isNotEmpty() } }.orEmpty()
        val narrators = books.firstNotNullOfOrNull { book -> book.narrators.takeIf { it.isNotEmpty() } }.orEmpty()
        val durationMs = books.firstNotNullOfOrNull { it.durationMs }
        // Without a length on both sides, title and author alone renormalise to a full score; such a
        // candidate can never be Strong (Simon, 2026-10-05).
        val hasLengthEvidence = subject.durationMs != null && durationMs != null
        val score =
            MatchScorer.score(
                subject.identity(),
                BookMatch(
                    title = title,
                    author = authors.firstOrNull(),
                    durationMs = durationMs,
                    narrators = narrators,
                    score = 0.0,
                ),
            )
        val candidate =
            BookCandidate(
                key = BookCandidateKey(everyone.map { it.toRef() }.distinctBy { it.provider }),
                title = title,
                subtitle = books.firstNotNullOfOrNull { it.subtitle?.takeIf(String::isNotBlank) },
                authors = authors,
                narrators = narrators,
                durationMs = durationMs,
                year = books.firstNotNullOfOrNull { it.releaseDate?.run { take(YEAR_DIGITS).toIntOrNull() } },
                format = books.firstNotNullOfOrNull { it.format },
                chapterCount = books.firstNotNullOfOrNull { it.chapterCount },
                coverUrl = everyone.firstNotNullOfOrNull { it.book.coverUrl?.takeIf(String::isNotBlank) },
                foundIn = everyone.map { it.toFoundIn() }.distinctBy { it.source.id },
                tier = if (score >= STRONG_SCORE && hasLengthEvidence) MatchTier.STRONG else MatchTier.MAYBE,
                score = score,
                isBest = false,
                isCurrentLink = books.any { it.viaLink },
                reasons = emptyList(),
            )
        return candidate.copy(
            reasons = reasons(subject, candidate, otherStore(books.mapNotNull { it.region }, searchRegion)),
        )
    }

    private fun SourcedHit.toRef(): ExternalRef = ExternalRef(source.presentedAs().value, book.key, book.region)

    private fun SourcedHit.toFoundIn(): FoundIn = FoundIn(source.toMetadataSource(), book.region)

    /** The store a candidate was found in when none of its hits came from the store searched; else null. */
    private fun otherStore(
        regions: List<String>,
        searchRegion: String,
    ): String? {
        if (regions.any { it.equals(searchRegion, ignoreCase = true) }) return null
        return regions.firstOrNull()
    }

    /**
     * Why the candidate ranks where it does. Within each polarity: narrator, length, chapters, store, edition.
     * A Strong candidate leads with what agrees; a Maybe leads with what differs.
     */
    private fun reasons(
        subject: FindSubject,
        candidate: BookCandidate,
        otherStore: String?,
    ): List<MatchReason> {
        val agree = mutableListOf<MatchReason>()
        val differ = mutableListOf<MatchReason>()
        if (subject.narrators.isNotEmpty() && candidate.narrators.isNotEmpty()) {
            if (MatchScorer.sameNames(subject.narrators, candidate.narrators)) {
                agree += MatchReason.SameNarrator
            } else {
                differ += MatchReason.DifferentNarrators
            }
        }
        when (val length = lengthReason(subject.durationMs, candidate.durationMs)) {
            null -> differ += MatchReason.LengthUnknown
            is MatchReason.LengthDiffers -> differ += length
            else -> agree += length
        }
        chapterReason(subject.chapterCount, candidate.chapterCount)?.let {
            if (it is MatchReason.DifferentChapterCount) differ += it else agree += it
        }
        otherStore?.let { differ += MatchReason.DifferentStore(MetadataLocale(it)) }
        val theirFormat = candidate.format
        if (subject.format != null && theirFormat != null && subject.format != theirFormat) {
            differ += MatchReason.DifferentEdition(theirFormat)
        }
        return if (candidate.tier == MatchTier.STRONG) agree + differ else differ + agree
    }

    private fun lengthReason(
        yours: Long?,
        theirs: Long?,
    ): MatchReason? {
        if (yours == null || theirs == null) return null
        val delta = theirs - yours
        return when {
            abs(delta) <= SAME_LENGTH_MS -> MatchReason.SameLength
            abs(delta) <= NEAR_LENGTH_MS -> MatchReason.LengthWithin(ceil(abs(delta) / MS_PER_MINUTE).toInt())
            else -> MatchReason.LengthDiffers((delta / MS_PER_MINUTE).roundToInt())
        }
    }

    private fun chapterReason(
        yours: Int?,
        theirs: Int?,
    ): MatchReason? =
        when {
            yours == null || theirs == null -> null
            yours == theirs -> MatchReason.SameChapterCount(theirs)
            else -> MatchReason.DifferentChapterCount(theirs)
        }
}
