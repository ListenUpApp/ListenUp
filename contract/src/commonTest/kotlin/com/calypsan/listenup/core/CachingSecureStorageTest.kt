package com.calypsan.listenup.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

/**
 * In-memory fake that counts how often each key is read from the delegate, so tests can prove
 * the cache coalesces and absorbs reads. [read] yields before returning so concurrent callers
 * actually interleave — a missing cache or missing lock would show up as multiple delegate reads.
 */
private class CountingSecureStorage : SecureStorage {
    val store = mutableMapOf<String, String>()
    val readCounts = mutableMapOf<String, Int>()

    override suspend fun save(
        key: String,
        value: String,
    ) {
        store[key] = value
    }

    override suspend fun read(key: String): String? {
        readCounts[key] = (readCounts[key] ?: 0) + 1
        yield()
        return store[key]
    }

    override suspend fun delete(key: String) {
        store.remove(key)
    }

    override suspend fun clear() {
        store.clear()
    }
}

class CachingSecureStorageTest :
    FunSpec({

        test("concurrent misses coalesce into a single delegate read") {
            runTest {
                val delegate = CountingSecureStorage().apply { store["k"] = "v" }
                val caching = CachingSecureStorage(delegate)

                val jobs = List(100) { launch { caching.read("k") } }
                jobs.joinAll()

                delegate.readCounts["k"] shouldBe 1
            }
        }

        test("a second read is served from cache without consulting the delegate") {
            runTest {
                val delegate = CountingSecureStorage().apply { store["k"] = "v" }
                val caching = CachingSecureStorage(delegate)

                caching.read("k") shouldBe "v"
                caching.read("k") shouldBe "v"

                delegate.readCounts["k"] shouldBe 1
            }
        }

        test("save is write-through — a later read returns the new value with no delegate read") {
            runTest {
                val delegate = CountingSecureStorage().apply { store["k"] = "v1" }
                val caching = CachingSecureStorage(delegate)

                caching.read("k") shouldBe "v1"
                caching.save("k", "v2")
                caching.read("k") shouldBe "v2"

                delegate.store["k"] shouldBe "v2"
                delegate.readCounts["k"] shouldBe 1
            }
        }

        test("delete invalidates the cache so the delegate is consulted again") {
            runTest {
                val delegate = CountingSecureStorage().apply { store["k"] = "v" }
                val caching = CachingSecureStorage(delegate)

                caching.read("k") shouldBe "v"
                caching.delete("k")
                caching.read("k") shouldBe null

                delegate.readCounts["k"] shouldBe 2
            }
        }

        test("a null delegate read is not cached as a permanent miss") {
            runTest {
                val delegate = CountingSecureStorage()
                val caching = CachingSecureStorage(delegate)

                caching.read("k") shouldBe null
                caching.save("k", "v")
                caching.read("k") shouldBe "v"
            }
        }
    })
