package com.calypsan.listenup.server.e2e

import com.calypsan.listenup.core.SecureStorage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Test-only [SecureStorage] backed by an in-memory map.
 *
 * Production uses platform Keychain / EncryptedSharedPreferences; the contract
 * is the same suspend-based key/value API. Mutex serialises access so concurrent
 * coroutines (e.g. the bearer plugin's loadTokens + a parallel save) don't race.
 */
internal class InMemorySecureStorage : SecureStorage {
    private val mutex = Mutex()
    private val store = mutableMapOf<String, String>()

    override suspend fun save(
        key: String,
        value: String,
    ) {
        mutex.withLock { store[key] = value }
    }

    override suspend fun read(key: String): String? = mutex.withLock { store[key] }

    override suspend fun delete(key: String) {
        mutex.withLock { store.remove(key) }
    }

    override suspend fun clear() {
        mutex.withLock { store.clear() }
    }
}
