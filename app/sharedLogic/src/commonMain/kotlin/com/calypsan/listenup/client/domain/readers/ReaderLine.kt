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

    /**
     * The person finished the book at the instant recorded in [finishedAtMs] (epoch ms). When
     * [alsoOnHardcover], they logged the same listen on Hardcover too, and every surface says "Also on
     * Hardcover" on this one line rather than showing the Hardcover read as a second.
     */
    data class Finished(
        val finishedAtMs: Long,
        val alsoOnHardcover: Boolean = false,
    ) : ReaderLineKind

    /**
     * The person logged a read of the book on Hardcover, finished at [finishedAtMs] (epoch ms), and
     * ListenUp pulled it (#601 B3). Every surface shows it with a "Hardcover" badge — never as a finish
     * in ListenUp.
     */
    data class FinishedOnHardcover(
        val finishedAtMs: Long,
    ) : ReaderLineKind

    /** The person rated the book without reading it here (e.g. imported history). */
    data object Rated : ReaderLineKind
}

/**
 * Flattens readers into display lines: each reader yields a [ReaderLineKind.Reading] line when they
 * are currently reading, plus one line per finish — [ReaderLineKind.Finished] for ListenUp's,
 * [ReaderLineKind.FinishedOnHardcover] for a read pulled from Hardcover — then lines for people who
 * only rated. Ordering: all reading lines first, then all finished lines newest-first across readers
 * and both kinds, then rated-only lines. A person's rating rides on their first line.
 */
fun flattenToLines(readers: List<Reader>): List<ReaderLine> {
    val reading =
        readers
            .filter { it.currentProgressPct != null }
            .map { reader ->
                ReaderLine(
                    userId = reader.userId,
                    name = reader.displayName,
                    isYou = reader.isYou,
                    kind = ReaderLineKind.Reading(reader.currentProgressPct),
                )
            }
    val finished =
        readers
            .flatMap { it.finishedLines() }
            .sortedByDescending { (finishedAtMs, _) -> finishedAtMs }
            .map { (_, line) -> line }
    val ratedOnly =
        readers
            .filter { reader ->
                reader.currentProgressPct == null && (reader.finishes + reader.hardcoverFinishes).isEmpty() &&
                    reader.rating != null
            }.map { reader ->
                ReaderLine(
                    userId = reader.userId,
                    name = reader.displayName,
                    isYou = reader.isYou,
                    kind = ReaderLineKind.Rated,
                )
            }
    val ratingsByUser = readers.mapNotNull { r -> r.rating?.let { r.userId to it } }.toMap()
    val seen = mutableSetOf<String>()
    return (reading + finished + ratedOnly).map { line ->
        if (seen.add(line.userId)) line.copy(rating = ratingsByUser[line.userId]) else line
    }
}

/** One reader's finished lines — ListenUp's finishes and Hardcover's reads alike — each with the instant it sorts by. */
private fun Reader.finishedLines(): List<Pair<Long, ReaderLine>> =
    finishes.map { it to ReaderLine(userId = userId, name = displayName, isYou = isYou, kind = finishedKind(it)) } +
        hardcoverFinishes.map { finishedAtMs ->
            finishedAtMs to
                ReaderLine(
                    userId = userId,
                    name = displayName,
                    isYou = isYou,
                    kind = ReaderLineKind.FinishedOnHardcover(finishedAtMs),
                )
        }

/** A ListenUp finish, saying so when the reader logged the same listen on Hardcover too. */
private fun Reader.finishedKind(finishedAtMs: Long): ReaderLineKind.Finished =
    ReaderLineKind.Finished(finishedAtMs, alsoOnHardcover = finishedAtMs in finishesAlsoOnHardcover)
