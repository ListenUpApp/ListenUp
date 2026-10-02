package com.calypsan.listenup.server.hardcover

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private const val DEFAULT_CAPACITY = 500
private val DETAILS_TTL = 6.hours
private val RESOLUTION_TTL = 10.minutes

/** Which Hardcover book a book being matched is, or that Hardcover has none it's confident of. */
internal sealed interface HardcoverResolution {
    data class Book(
        val hcBookId: Long,
    ) : HardcoverResolution

    data object NoMatch : HardcoverResolution
}

/**
 * What the metadata source remembers (#1542), so one match preview — core, genres, series and moods,
 * each asked separately — costs one lookup and one details call, and the apply that follows costs none.
 * Catalogue data is the same for everyone, so one bounded map serves all; nothing is persisted. Which
 * book a match is expires after ten minutes (a link may change); a book's details after six hours. Past
 * [capacity] the oldest entry goes first. Alongside [HardcoverCatalogCache], which names linked books.
 */
class HardcoverMetadataCache(
    private val clock: Clock = Clock.System,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private class Stamped<T>(
        val value: T,
        val at: Instant,
    )

    private val lock = SynchronizedObject()
    private val details = LinkedHashMap<Long, Stamped<HardcoverBookDetails>>()
    private val resolutions = LinkedHashMap<String, Stamped<HardcoverResolution>>()

    /** [hcBookId]'s details, if fetched within six hours. */
    fun details(hcBookId: Long): HardcoverBookDetails? = synchronized(lock) { details.fresh(hcBookId, DETAILS_TTL) }

    /** Keeps [found] under its Hardcover id. */
    fun rememberDetails(found: HardcoverBookDetails) = synchronized(lock) { details.keep(found.hcBookId, found) }

    internal fun resolution(key: String): HardcoverResolution? =
        synchronized(lock) {
            resolutions.fresh(key, RESOLUTION_TTL)
        }

    internal fun rememberResolution(
        key: String,
        resolution: HardcoverResolution,
    ) = synchronized(lock) { resolutions.keep(key, resolution) }

    private fun <K, V> LinkedHashMap<K, Stamped<V>>.fresh(
        key: K,
        ttl: Duration,
    ): V? {
        val entry = get(key) ?: return null
        if (clock.now() - entry.at > ttl) {
            remove(key)
            return null
        }
        return entry.value
    }

    private fun <K, V> LinkedHashMap<K, Stamped<V>>.keep(
        key: K,
        value: V,
    ) {
        remove(key)
        put(key, Stamped(value, clock.now()))
        while (size > capacity) remove(keys.first())
    }
}
