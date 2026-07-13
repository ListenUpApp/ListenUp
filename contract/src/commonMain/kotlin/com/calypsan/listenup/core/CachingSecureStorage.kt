package com.calypsan.listenup.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A [SecureStorage] decorator that keeps an in-memory cache of decrypted values.
 *
 * **Why this exists.** On Android, [read] decrypts via the hardware-backed Android Keystore, which
 * has a small pool of concurrent operation slots. Screens such as the contributors page issue a
 * burst of concurrent `server_url` / `active_url` reads through the API client factory; without
 * coalescing, each one is a fresh `Cipher.doFinal`. The burst exhausts the slot pool, the Keystore
 * **prunes** in-flight operations, and the pruned ops surface as `IllegalBlockSizeException` — log
 * spam at best, a spurious "value unavailable" null at worst.
 *
 * This decorator fixes that structurally: the [Mutex] both **caches** the result (so repeat reads
 * never touch the Keystore) and **serializes** the underlying decrypt (so the slot pool can never
 * be flooded, even on the very first concurrent miss). Concurrent misses for the same key collapse
 * into a single delegate read; everyone else returns the cached plaintext.
 *
 * **Security is unchanged.** The cache holds secrets that are already resident in process memory
 * the moment they are decrypted for use — it changes nothing about at-rest encryption, which the
 * platform delegate still owns. Values live only for the process lifetime and are dropped on
 * [delete] / [clear]; the delegate remains the source of truth on disk.
 *
 * @param delegate the platform [SecureStorage] that performs the real encrypted I/O.
 */
class CachingSecureStorage(
    private val delegate: SecureStorage,
) : SecureStorage {
    private val cache = mutableMapOf<String, String>()
    private val mutex = Mutex()

    override suspend fun save(
        key: String,
        value: String,
    ) = mutex.withLock {
        // Delegate write and cache update under ONE lock so concurrent writers can't land on disk in
        // one order and in the cache in another, leaving the cache disagreeing with the delegate
        // (C7 — a rotated token reverting under a racing write).
        delegate.save(key, value)
        cache[key] = value
    }

    override suspend fun read(key: String): String? =
        mutex.withLock {
            cache[key] ?: delegate.read(key)?.also { cache[key] = it }
        }

    override suspend fun delete(key: String) =
        mutex.withLock {
            delegate.delete(key)
            cache.remove(key)
            Unit
        }

    override suspend fun clear() =
        mutex.withLock {
            delegate.clear()
            cache.clear()
        }
}
