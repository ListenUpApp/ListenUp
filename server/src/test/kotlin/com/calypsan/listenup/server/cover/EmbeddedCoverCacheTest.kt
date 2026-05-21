package com.calypsan.listenup.server.cover

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedArtwork
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for [EmbeddedCoverCache] — the LRU cache backing embedded-artwork
 * cover serving.
 */
class EmbeddedCoverCacheTest :
    FunSpec({

        fun artwork(tag: String): EmbeddedArtwork = EmbeddedArtwork(mime = "image/jpeg", bytes = tag.toByteArray())

        test("getOrCompute invokes the loader exactly once per key across repeated reads") {
            runTest {
                val cache = EmbeddedCoverCache()
                val loaderCalls = AtomicInteger(0)
                val key = BookId("book-1")

                val first =
                    cache.getOrCompute(key) {
                        loaderCalls.incrementAndGet()
                        artwork("payload")
                    }
                val second =
                    cache.getOrCompute(key) {
                        loaderCalls.incrementAndGet()
                        artwork("DIFFERENT")
                    }

                loaderCalls.get() shouldBe 1
                first shouldBe artwork("payload")
                second shouldBe artwork("payload")
            }
        }

        test("getOrCompute does not cache a null loader result") {
            runTest {
                val cache = EmbeddedCoverCache()
                val loaderCalls = AtomicInteger(0)
                val key = BookId("book-missing")

                cache.getOrCompute(key) {
                    loaderCalls.incrementAndGet()
                    null
                }
                cache.getOrCompute(key) {
                    loaderCalls.incrementAndGet()
                    artwork("now-present")
                }

                loaderCalls.get() shouldBe 2
            }
        }

        test("getOrCompute evicts the least-recently-used entry past maxSize") {
            runTest {
                val cache = EmbeddedCoverCache(maxSize = 2)

                cache.getOrCompute(BookId("a")) { artwork("a") }
                cache.getOrCompute(BookId("b")) { artwork("b") }
                // Touch "a" so "b" becomes least-recently-used.
                cache.getOrCompute(BookId("a")) { artwork("a-RECOMPUTED") }
                // Insert "c" — should evict "b", not "a".
                cache.getOrCompute(BookId("c")) { artwork("c") }

                // Probe "a" first — it must still be resident (loader must not run).
                val aLoaderCalls = AtomicInteger(0)
                cache.getOrCompute(BookId("a")) {
                    aLoaderCalls.incrementAndGet()
                    artwork("a-SHOULD-NOT-RUN")
                }
                // Then probe "b" — it was evicted, so the loader must run.
                val bLoaderCalls = AtomicInteger(0)
                cache.getOrCompute(BookId("b")) {
                    bLoaderCalls.incrementAndGet()
                    artwork("b-RELOADED")
                }

                aLoaderCalls.get() shouldBe 0 // still resident
                bLoaderCalls.get() shouldBe 1 // evicted → recomputed
            }
        }

        test("maxSize is honored — the eldest entry is evicted past the cap") {
            runTest {
                val cache = EmbeddedCoverCache(maxSize = 2)

                cache.getOrCompute(BookId("a")) { artwork("a") }
                cache.getOrCompute(BookId("b")) { artwork("b") }
                cache.getOrCompute(BookId("c")) { artwork("c") }

                // Probe the survivors first — a get() never inserts, so it can't
                // perturb residency. "b" and "c" remain resident past the cap.
                val cLoaderCalls = AtomicInteger(0)
                cache.getOrCompute(BookId("c")) {
                    cLoaderCalls.incrementAndGet()
                    artwork("c-SHOULD-NOT-RUN")
                }
                val bLoaderCalls = AtomicInteger(0)
                cache.getOrCompute(BookId("b")) {
                    bLoaderCalls.incrementAndGet()
                    artwork("b-SHOULD-NOT-RUN")
                }
                // "a" is the eldest — evicted, so its loader must run again.
                val aLoaderCalls = AtomicInteger(0)
                cache.getOrCompute(BookId("a")) {
                    aLoaderCalls.incrementAndGet()
                    artwork("a-RELOADED")
                }

                cLoaderCalls.get() shouldBe 0 // still resident
                bLoaderCalls.get() shouldBe 0 // still resident
                aLoaderCalls.get() shouldBe 1 // evicted → recomputed
            }
        }

        test("an evicted entry also evicts its keyLock") {
            runTest {
                val cache = EmbeddedCoverCache(maxSize = 1)

                cache.getOrCompute(BookId("first")) { artwork("first") }
                cache.getOrCompute(BookId("second")) { artwork("second") }

                // "first" was evicted from entries — its keyLock must go with it,
                // so keyLocks stays bounded alongside entries.
                cache.hasKeyLock(BookId("first")) shouldBe false
                cache.hasKeyLock(BookId("second")) shouldBe true
            }
        }

        test("concurrent getOrCompute for the same key invokes the loader exactly once") {
            runTest {
                val cache = EmbeddedCoverCache()
                val loaderCalls = AtomicInteger(0)
                val key = BookId("hot-key")

                val results =
                    coroutineScope {
                        (1..32)
                            .map {
                                async {
                                    cache.getOrCompute(key) {
                                        loaderCalls.incrementAndGet()
                                        artwork("singleton")
                                    }
                                }
                            }.awaitAll()
                    }

                loaderCalls.get() shouldBe 1
                results.all { it == artwork("singleton") } shouldBe true
            }
        }
    })
