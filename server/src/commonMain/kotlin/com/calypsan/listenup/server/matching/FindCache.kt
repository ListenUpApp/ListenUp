package com.calypsan.listenup.server.matching

import com.calypsan.listenup.server.metadata.spi.FindAnswer
import com.calypsan.listenup.server.metadata.spi.FindLookup
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.PersonAnswer
import com.calypsan.listenup.server.metadata.spi.PersonLookup
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
    clock: Clock = Clock.System,
    ttl: Duration = FIND_CACHE_TTL,
    capacity: Int = FIND_CACHE_CAPACITY,
) : AnswerCache<FindCache.Key, FindAnswer>(clock, ttl, capacity) {
    /** One source's answer to one lookup, in one store when the source has stores. */
    data class Key(
        val source: MetadataProviderId,
        val lookup: FindLookup,
        val region: String?,
    )
}

/** [FindCache]'s counterpart for people Finds: one source's answer to one person lookup, in one store. */
internal class PeopleFindCache(
    clock: Clock = Clock.System,
    ttl: Duration = FIND_CACHE_TTL,
    capacity: Int = FIND_CACHE_CAPACITY,
) : AnswerCache<PeopleFindCache.Key, PersonAnswer>(clock, ttl, capacity) {
    /** One source's answer to one person lookup in one store. */
    data class Key(
        val source: MetadataProviderId,
        val lookup: PersonLookup,
        val region: String,
    )
}

/** A bounded, expiring map of source answers — what [FindCache] and [PeopleFindCache] share. */
internal open class AnswerCache<K : Any, V : Any>(
    private val clock: Clock,
    private val ttl: Duration,
    private val capacity: Int,
) {
    private class Stamped<V>(
        val answer: V,
        val at: Instant,
    )

    private val lock = SynchronizedObject()
    private val entries = LinkedHashMap<K, Stamped<V>>()

    /** [key]'s answer, if one was kept within the TTL. */
    fun get(key: K): V? =
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
        key: K,
        answer: V,
    ) {
        synchronized(lock) {
            entries.remove(key)
            entries[key] = Stamped(answer, clock.now())
            while (entries.size > capacity) entries.remove(entries.keys.first())
        }
    }
}
