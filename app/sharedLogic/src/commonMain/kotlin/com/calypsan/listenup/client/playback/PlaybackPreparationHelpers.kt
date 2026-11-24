package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import com.calypsan.listenup.client.data.local.db.AudioFileEntity
import com.calypsan.listenup.client.data.local.db.BookContributorCrossRef
import com.calypsan.listenup.client.data.local.db.BookWithContributors
import com.calypsan.listenup.client.data.local.db.ContributorEntity
import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.core.BookId
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/** One second in milliseconds. */
private const val MS_PER_SECOND = 1000L

/** One minute in milliseconds. */
private const val MS_PER_MINUTE = 60_000L

/** One hour in milliseconds. */
private const val MS_PER_HOUR = 3_600_000L

/** 24 hours in milliseconds — ceiling above which a single-file duration is suspicious. */
private const val MAX_PLAUSIBLE_FILE_DURATION_MS = 86_400_000L

/**
 * The resume point resolved across the local Room row and the server-authoritative
 * position, carrying only what resume needs: [positionMs] and the [isFinished] flag
 * (finished → start at 0 for re-read).
 */
internal data class ResolvedResumePosition(
    val positionMs: Long,
    val isFinished: Boolean,
    /**
     * When the WINNING side was last played. Carried out of the resolver rather than recomputed by
     * callers: the resolver already picks by this key, so deriving it a second time at the call
     * site would be a second copy of the conflict rule, free to drift from this one.
     */
    val lastPlayedAtMs: Long,
)

/**
 * Reconcile the [local] Room resume row against the [server]-authoritative position from
 * [com.calypsan.listenup.api.PlaybackService.prepare], newer wins by `lastPlayedAt` — the
 * same conflict key the playback-positions sync domain uses.
 *
 * Position is sacred. Device B can open a book before its catch-up drains, so its local
 * row may be stale relative to the position `prepare()` just returned from the server. If
 * the stale local row won here, starting playback would stamp it `lastPlayedAt = now`,
 * making it globally newest and permanently discarding device A's newer progress. Choosing
 * the fresher source at resume time closes that race.
 *
 * Returns null only when both sources are absent (never-played book → resume at 0
 * upstream). When only one is present it wins by default; ties favour the local row (its
 * value is at least as fresh and will re-push to the server on the next save).
 */
internal fun resolveResumePosition(
    local: PlaybackPosition?,
    server: PlaybackPositionSyncPayload?,
): ResolvedResumePosition? {
    val fromLocal = local?.run { ResolvedResumePosition(positionMs, isFinished, effectiveLastPlayedAtMs) }
    val fromServer = server?.run { ResolvedResumePosition(positionMs, finished, lastPlayedAt) }
    return when {
        fromLocal == null -> fromServer
        fromServer == null -> fromLocal
        fromServer.lastPlayedAtMs > fromLocal.lastPlayedAtMs -> fromServer
        else -> fromLocal
    }
}

/**
 * The (id, name) refs for a book's contributors in the given [role] — the navigable form of
 * [joinContributorNames], distinct-by-id and preserving credit order, for the player's "Go to
 * Author / Narrator" menu.
 */
internal fun contributorRefs(
    contributors: List<ContributorEntity>,
    contributorRoles: List<BookContributorCrossRef>,
    role: ContributorRole,
): List<PlaybackNavRef> {
    val contributorsById = contributors.associateBy { it.id }
    return contributorRoles
        .filter { it.role == role.apiValue }
        .mapNotNull { crossRef ->
            contributorsById[crossRef.contributorId]?.let { entity ->
                PlaybackNavRef(id = entity.id.value, name = crossRef.creditedAs ?: entity.name)
            }
        }.distinctBy { it.id }
}

/**
 * Comma-join the display names of the [contributors] credited with [role] on a
 * book, preferring each cross-ref's `creditedAs` (alias attribution) over the
 * contributor's canonical name. Returns an empty string when no contributor holds
 * the role.
 */
internal fun joinContributorNames(
    contributors: List<ContributorEntity>,
    contributorRoles: List<BookContributorCrossRef>,
    role: ContributorRole,
): String {
    val contributorsById = contributors.associateBy { it.id }
    return contributorRoles
        .filter { it.role == role.apiValue }
        .mapNotNull { crossRef ->
            contributorsById[crossRef.contributorId]?.let { entity ->
                crossRef.creditedAs ?: entity.name
            }
        }.distinct()
        .joinToString(", ")
}

/**
 * Derive the comma-joined author display name from the book's contributor
 * roles. Falls back to "Unknown Author" when no author is found.
 */
internal fun deriveAuthorName(bookWithContributors: BookWithContributors): String =
    joinContributorNames(
        bookWithContributors.contributors,
        bookWithContributors.contributorRoles,
        ContributorRole.AUTHOR,
    ).ifEmpty { "Unknown Author" }

/**
 * Derive the comma-joined narrator display name, or an empty string when the
 * book has no narrator — the player hides the "Narrated by" line in that case.
 */
internal fun deriveNarratorName(bookWithContributors: BookWithContributors): String =
    joinContributorNames(
        bookWithContributors.contributors,
        bookWithContributors.contributorRoles,
        ContributorRole.NARRATOR,
    )

/**
 * Log per-file diagnostics for [audioFiles], flagging invalid (≤0) and
 * suspiciously large (>24h) durations and reporting the total.
 */
internal fun logAudioFileDiagnostics(
    bookId: BookId,
    audioFiles: List<AudioFileEntity>,
) {
    logger.debug { "=== Audio Files for book ${bookId.value} ===" }
    var totalDuration = 0L
    audioFiles.forEachIndexed { index, file ->
        logger.debug {
            "  File[$index]: id=${file.id}, filename=${file.filename}, " +
                "duration=${file.duration}ms (${file.duration / MS_PER_SECOND}s), " +
                "size=${file.size}, format=${file.format}"
        }
        if (file.duration <= 0) {
            logger.warn { "  ⚠️ WARNING: File[$index] has invalid duration: ${file.duration}" }
        }
        if (file.duration > MAX_PLAUSIBLE_FILE_DURATION_MS) {
            logger.warn {
                "  ⚠️ WARNING: File[$index] has suspiciously large duration: " +
                    "${file.duration}ms (${file.duration / MS_PER_HOUR}h)"
            }
        }
        totalDuration += file.duration
    }
    logger.debug {
        "=== Total calculated duration: ${totalDuration}ms " +
            "(${totalDuration / MS_PER_SECOND}s / ${totalDuration / MS_PER_MINUTE}min) ==="
    }
}

/**
 * A streaming prepare that failed for lack of network is [PlaybackError.ServerUnreachable], so the player keeps
 * reconnecting and says so; any other failure is not the network's to cure and passes through unchanged.
 */
internal fun AppError.asPlaybackFailure(): AppError =
    if (this is TransportError && isRetryable) {
        PlaybackError.ServerUnreachable(debugInfo = "$code: ${debugInfo.orEmpty()}")
    } else {
        this
    }
