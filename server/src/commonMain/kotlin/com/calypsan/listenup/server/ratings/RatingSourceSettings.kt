package com.calypsan.listenup.server.ratings

import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import kotlin.time.Duration.Companion.days

/**
 * The admin's per-source on/off switch *and* per-source health for outside ratings, persisted in
 * [ServerSettingsRepository]'s generic key/value store under `ratings.source.<SOURCE>.*`.
 * [isEnabled] defaults to enabled when unset — a source an admin has never touched should fetch,
 * not silently sit dark.
 *
 * Health lives here, per source, rather than being derived from `book_external_ratings` rows: a row
 * only exists once a source has *succeeded* at least once for *some* book, so row-derived health is
 * blind to a source that has never worked at all (wrong region, unreachable from day one — every
 * fetch fails, no row is ever created, and an admin reading row-derived health would see a
 * healthy-looking source that has simply never run). Recording health as its own per-source setting
 * means [recordFailure] always has somewhere to write, independent of whether any book has ever
 * matched.
 */
class RatingSourceSettings(
    private val settings: ServerSettingsRepository,
) {
    /** Whether [source] is currently enabled — `true` when the admin has never set it either way. */
    suspend fun isEnabled(source: ExternalRatingSource): Boolean =
        settings.getValue(key(source, "enabled"))?.toBooleanStrictOrNull() ?: true

    /** Flips the admin's on/off switch for [source]. */
    suspend fun setEnabled(
        source: ExternalRatingSource,
        enabled: Boolean,
    ) {
        settings.setValue(key(source, "enabled"), enabled.toString())
        // Turning a source back on is the admin's manual way out of an automatic pause.
        if (enabled) clearPause(source)
    }

    /**
     * Records a successful fetch of [source] at [at] — a rating, or a confident "no rating" both
     * count: either proves the source answered. Clears any prior [recordFailure].
     */
    suspend fun recordSuccess(
        source: ExternalRatingSource,
        at: Long,
    ) {
        settings.setValue(key(source, "lastFetchedAt"), at.toString())
        settings.setValue(key(source, "lastError"), "")
        clearPause(source)
    }

    /** Records that [source] failed at [at] with [message] — never clears [recordSuccess]'s last-fetched instant. */
    suspend fun recordFailure(
        source: ExternalRatingSource,
        message: String,
        at: Long,
    ) {
        settings.setValue(key(source, "lastError"), message)
        settings.setValue(key(source, "lastErrorAt"), at.toString())
        val failures = (settings.getValue(key(source, "consecutiveFailures"))?.toIntOrNull() ?: 0) + 1
        settings.setValue(key(source, "consecutiveFailures"), failures.toString())
        if (failures >= FAILURES_BEFORE_PAUSE) {
            settings.setValue(key(source, "pausedUntil"), (at + PAUSE.inWholeMilliseconds).toString())
        }
    }

    /** When [source]'s automatic pause ends, or `null` when it is not paused as of [now]. */
    suspend fun pausedUntil(
        source: ExternalRatingSource,
        now: Long,
    ): Long? = settings.getValue(key(source, "pausedUntil"))?.toLongOrNull()?.takeIf { it > now }

    private suspend fun clearPause(source: ExternalRatingSource) {
        settings.setValue(key(source, "consecutiveFailures"), "0")
        settings.setValue(key(source, "pausedUntil"), "")
    }

    /** [source]'s most recent successful-fetch instant, and its most recent error message, if any. */
    suspend fun health(source: ExternalRatingSource): Pair<Long?, String?> {
        val fetchedAt = settings.getValue(key(source, "lastFetchedAt"))?.toLongOrNull()
        val error = settings.getValue(key(source, "lastError"))?.takeIf { it.isNotBlank() }
        return fetchedAt to error
    }

    private companion object {
        const val FAILURES_BEFORE_PAUSE = 5
        val PAUSE = 7.days
    }

    private fun key(
        source: ExternalRatingSource,
        field: String,
    ) = "ratings.source.${source.name}.$field"
}
