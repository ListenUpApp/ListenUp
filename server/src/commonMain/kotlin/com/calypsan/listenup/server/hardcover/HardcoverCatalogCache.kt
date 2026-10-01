package com.calypsan.listenup.server.hardcover

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

private const val DEFAULT_CAPACITY = 1_000

/**
 * Names the Hardcover books users are linked to without asking Hardcover on every Book Detail visit.
 *
 * Catalog data is global, not per user, so one bounded map serves everyone. A search fills it for
 * free ([remember]: it already fetched each candidate's book), and a miss costs one paced `books`
 * lookup through the asking user's own token. Hardcover owns these titles and can change them, so
 * nothing is persisted: a restart simply refills it. Past [capacity] the oldest entry goes first.
 */
class HardcoverCatalogCache(
    private val graphQl: HardcoverGraphQlClient,
    private val rateLimiter: HardcoverRateLimiter,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val lock = SynchronizedObject()
    private val books = LinkedHashMap<Long, HardcoverCatalogBook>()

    /** Keeps [found] (newest last), dropping the oldest entries past [capacity]. */
    fun remember(found: Collection<HardcoverCatalogBook>) =
        synchronized(lock) {
            found.forEach { book ->
                books.remove(book.id)
                books[book.id] = book
            }
            while (books.size > capacity) books.remove(books.keys.first())
        }

    /** [hcBookId]'s entry if it's already known. Never asks Hardcover. */
    fun cached(hcBookId: Long): HardcoverCatalogBook? = synchronized(lock) { books[hcBookId] }

    /** [hcBookId]'s entry, asking Hardcover through [accessToken] on a miss; null when Hardcover can't say. */
    suspend fun describe(
        accessToken: String,
        hcBookId: Long,
    ): HardcoverCatalogBook? {
        cached(hcBookId)?.let { return it }
        rateLimiter.await()
        val found = graphQl.booksByIds(accessToken, listOf(hcBookId)).valueOr { return null }
        remember(found)
        return found.firstOrNull { it.id == hcBookId }
    }
}
