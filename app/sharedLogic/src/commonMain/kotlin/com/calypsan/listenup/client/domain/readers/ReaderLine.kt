package com.calypsan.listenup.client.domain.readers

import com.calypsan.listenup.client.domain.model.ListenerRating

/** One display line in the Readers list — a person in one state. */
data class ReaderLine(
    val userId: String,
    val name: String,
    val isYou: Boolean,
    val kind: ReaderLineKind,
    val rating: ListenerRating? = null,
)

/** What a [ReaderLine] is reporting about its person: mid-book, or done with it. */
sealed interface ReaderLineKind {
    /** The person is currently reading; [progressPct] is known when non-null. */
    data class Reading(
        val progressPct: Int?,
    ) : ReaderLineKind

    /** The person finished the book at the instant recorded in [finishedAtMs] (epoch ms). */
    data class Finished(
        val finishedAtMs: Long,
    ) : ReaderLineKind

    /** The person rated the book without reading it here (e.g. imported history). */
    data object Rated : ReaderLineKind
}

/**
 * Flattens readers into display lines: each reader yields a [ReaderLineKind.Reading] line when they
 * are currently reading, plus one [ReaderLineKind.Finished] line per finish, then lines for people
 * who only rated. Ordering: all reading lines first, then all finished lines newest-first across
 * readers, then rated-only lines. A person's rating rides on their first line.
 */
fun flattenToLines(readers: List<Reader>): List<ReaderLine> {
    val reading =
        readers
            .filter { it.currentProgressPct != null }
            .map { ReaderLine(it.userId, it.displayName, it.isYou, ReaderLineKind.Reading(it.currentProgressPct)) }
    val finished =
        readers
            .flatMap { r -> r.finishes.map { r to it } }
            .sortedByDescending { it.second }
            .map { (r, ts) -> ReaderLine(r.userId, r.displayName, r.isYou, ReaderLineKind.Finished(ts)) }
    val ratedOnly =
        readers
            .filter { it.currentProgressPct == null && it.finishes.isEmpty() && it.rating != null }
            .map { ReaderLine(it.userId, it.displayName, it.isYou, ReaderLineKind.Rated) }
    val ratingsByUser = readers.mapNotNull { r -> r.rating?.let { r.userId to it } }.toMap()
    val seen = mutableSetOf<String>()
    return (reading + finished + ratedOnly).map { line ->
        if (seen.add(line.userId)) line.copy(rating = ratingsByUser[line.userId]) else line
    }
}
