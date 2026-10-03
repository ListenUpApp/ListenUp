package com.calypsan.listenup.server.api

import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.time.Instant

/** How many calendar days apart, in the reader's home timezone, a ListenUp finish and a Hardcover read may be and still be one listen. */
internal const val SAME_LISTEN_WINDOW_DAYS = 3

/**
 * One reader's finishes of one book after pairing.
 *
 * @property alsoOnHardcover The ListenUp finishes paired with a Hardcover read, in the order of the
 *   finishes they came from.
 * @property hardcoverOnly The Hardcover reads nothing paired with, in their original order.
 */
internal data class PairedReads(
    val alsoOnHardcover: List<Long>,
    val hardcoverOnly: List<Long>,
)

/**
 * Pairs each Hardcover read 1:1 with the nearest unpaired ListenUp finish no more than
 * [SAME_LISTEN_WINDOW_DAYS] calendar days from it in [zone], so a listen logged in both places reads
 * as one.
 *
 * Deterministic: the closest pairs (by elapsed time) are taken first, and a tie goes to the pair
 * dated earlier. Pairing is 1:1 — a finish and a read are each used at most once — so two finishes
 * and one read is still two rows.
 *
 * @param own ListenUp finishes, epoch ms.
 * @param hardcover Pulled Hardcover reads, epoch ms.
 */
internal fun pairWithHardcover(
    own: List<Long>,
    hardcover: List<Long>,
    zone: TimeZone,
): PairedReads {
    val candidates =
        own.indices
            .flatMap { o -> hardcover.indices.map { h -> o to h } }
            .filter { (o, h) -> calendarDaysApart(own[o], hardcover[h], zone) <= SAME_LISTEN_WINDOW_DAYS }
            .sortedWith(
                compareBy<Pair<Int, Int>> { (o, h) -> abs(own[o] - hardcover[h]) }
                    .thenBy { (o, h) -> minOf(own[o], hardcover[h]) }
                    .thenBy { (o, _) -> own[o] }
                    .thenBy { (_, h) -> hardcover[h] },
            )
    val pairedOwn = mutableSetOf<Int>()
    val pairedHardcover = mutableSetOf<Int>()
    for ((o, h) in candidates) {
        if (o !in pairedOwn && h !in pairedHardcover) {
            pairedOwn += o
            pairedHardcover += h
        }
    }
    return PairedReads(
        alsoOnHardcover = own.filterIndexed { o, _ -> o in pairedOwn },
        hardcoverOnly = hardcover.filterIndexed { h, _ -> h !in pairedHardcover },
    )
}

private fun calendarDaysApart(
    a: Long,
    b: Long,
    zone: TimeZone,
): Int = abs(localDate(a, zone).daysUntil(localDate(b, zone)))

private fun localDate(
    epochMs: Long,
    zone: TimeZone,
) = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone).date
