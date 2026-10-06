package com.calypsan.listenup.server.matching

import com.calypsan.listenup.server.metadata.spi.FindAnswer
import com.calypsan.listenup.server.metadata.spi.FindLookup
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private val FIND_CACHE_TTL: Duration = 10.minutes
private const val FIND_CACHE_CAPACITY = 256

/**
 * Each source's last full answer to a Find, so a retry re-asks only the sources that didn't answer — "Retry
 * Hardcover" re-runs the whole Find, cheaply. A failure, a timeout or an unavailable source is never kept.
 * Catalogue answers are the same for everyone, so one bounded map serves all; nothing is persisted. Entries
 * expire after ten minutes; past capacity, the oldest goes first.
 */
internal class FindCache(
    private val clock: Clock = Clock.System,
    private val ttl: Duration = FIND_CACHE_TTL,
    private val capacity: Int = FIND_CACHE_CAPACITY,
) {
    /** One source's answer to one lookup, in one store when the source has stores. */
    data class Key(
        val source: MetadataProviderId,
        val lookup: FindLookup,
        val region: String?,
    )

    private class Stamped(
        val answer: FindAnswer,
        val at: Instant,
    )

    private val lock = SynchronizedObject()
    private val entries = LinkedHashMap<Key, Stamped>()

    /** [key]'s answer, if one was kept within the TTL. */
    fun get(key: Key): FindAnswer? =
        synchronized(lock) {
            val entry = entries[key]
            when {
                entry == null -> {
                    null
                }

                clock.now() - entry.at > ttl -> {
                    entries.remove(key)
                    null
                }

                else -> {
                    entry.answer
                }
            }
        }

    /** Keeps [answer] for [key]. */
    fun put(
        key: Key,
        answer: FindAnswer,
    ) {
        synchronized(lock) {
            entries.remove(key)
            entries[key] = Stamped(answer, clock.now())
            while (entries.size > capacity) entries.remove(entries.keys.first())
        }
    }
}
