package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.sync.SyncPage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.KSerializer

private val logger = KotlinLogging.logger {}

/**
 * One row of a decoded [SyncPage], in wire order: either the typed value, or a row this build could
 * not decode. Both variants carry `T` (rather than `Undecodable : DecodedRow<Nothing>` under an
 * `out` projection) so every `is`/smart-cast site infers the element type with no variance puzzle.
 */
internal sealed interface DecodedRow<T : Any> {
    /** A row that decoded to [value]. */
    data class Decoded<T : Any>(
        val value: T,
    ) : DecodedRow<T>

    /** A row that threw during decode. [reason] is the exception message, for diagnostics only. */
    data class Undecodable<T : Any>(
        val reason: String?,
    ) : DecodedRow<T>
}

/** A [SyncPage] decoded row-by-row: [rows] preserves wire order, [undecodableCount] is how many failed. */
internal data class DecodedPage<T : Any>(
    val rows: List<DecodedRow<T>>,
    val nextCursor: Long?,
    val hasMore: Boolean,
) {
    /** How many rows this build could not decode. */
    val undecodableCount: Int get() = rows.count { it is DecodedRow.Undecodable }

    /** The rows that decoded, in order — for the transient paths that have no cursor to protect. */
    val decoded: List<T>
        get() =
            buildList {
                for (row in rows) {
                    when (row) {
                        is DecodedRow.Decoded -> add(row.value)
                        is DecodedRow.Undecodable -> Unit
                    }
                }
            }
}

/**
 * Decodes a wire [SyncPage] into typed rows.
 *
 * This is the client half of the sync wire's one deliberate asymmetry: the envelope crosses
 * typed, the rows cross as encoded strings, and [serializer] — the handler's own concrete
 * payload serializer, the *same* commonMain `@Serializable` class the server encoded with —
 * turns them back into values. A renamed field still breaks both sides at compile time; only
 * the domain→type association is dynamic, and `SyncDomainRoundTripSpec` pins that.
 *
 * Decoding row-by-row (rather than the whole page in one pass) is what keeps first sync from
 * holding a page-sized JSON tree alongside the decoded rows — the shape that used to drive
 * memory spikes on a large initial library.
 *
 * Each row is decoded under its OWN guard. A row this build cannot decode — a wire enum grew a
 * member, a payload gained a required shape — becomes [DecodedRow.Undecodable] rather than throwing
 * out of the whole page. Without that, one such row folds the pull to `AppResult.Failure` BEFORE any
 * cursor advance, so the next pass re-fetches the identical `?since=` page and fails identically:
 * the domain freezes permanently on that device. This mirrors what the live-frame path already does
 * ([SyncEventDispatcher]); the caller applies the same OptOut-freeze / digest-backstop policy an
 * apply failure gets.
 */
internal fun <T : Any> SyncPage.toDecodedPage(serializer: KSerializer<T>): DecodedPage<T> =
    DecodedPage(
        rows =
            items.map { raw ->
                try {
                    DecodedRow.Decoded(contractJson.decodeFromString(serializer, raw))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "Undecodable catch-up row in domain '$domain'; skipping it" }
                    DecodedRow.Undecodable<T>(e.message)
                }
            },
        nextCursor = nextCursor,
        hasMore = hasMore,
    )
