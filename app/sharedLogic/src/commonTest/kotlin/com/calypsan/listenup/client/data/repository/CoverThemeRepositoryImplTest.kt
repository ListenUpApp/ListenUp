package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.client.core.color.GREEN
import com.calypsan.listenup.client.core.color.IOS_STYLE
import com.calypsan.listenup.client.core.color.NEAR_CORAL
import com.calypsan.listenup.client.core.color.WAY_OF_KINGS
import com.calypsan.listenup.client.core.color.coverOf
import com.calypsan.listenup.client.data.covertheme.CoverPixelReader
import com.calypsan.listenup.client.data.covertheme.CoverPixels
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

/** Spec §2.3 caching and R4: a first visit decodes once, off the caller's thread; a revisit is instant. */
class CoverThemeRepositoryImplTest :
    FunSpec({
        class CountingReader(
            private val pixelsFor: (String?) -> CoverPixels?,
        ) : CoverPixelReader {
            val requests = mutableListOf<Triple<BookId, String?, Int>>()

            override suspend fun read(
                bookId: BookId,
                coverHash: String?,
                maxEdge: Int,
            ): CoverPixels? {
                requests += Triple(bookId, coverHash, maxEdge)
                return pixelsFor(coverHash)
            }
        }

        fun pixels(vararg parts: Pair<String, Int>) = coverOf(*parts).let { CoverPixels(width = it.size, height = 1, argb = it) }

        val book = BookId("b1")

        test("a first visit asks the reader for a 64-pixel sample and derives both appearances") {
            runTest {
                val reader = CountingReader { pixels(*WAY_OF_KINGS) }
                val repository = CoverThemeRepositoryImpl(reader, decodeDispatcher = StandardTestDispatcher(testScheduler))

                repository.observe(book, "h1", IOS_STYLE).test {
                    awaitItem().shouldNotBeNull()
                    awaitComplete()
                }
                reader.requests shouldBe listOf(Triple(book, "h1", 64))
            }
        }

        test("a revisit is answered from memory, synchronously and by observe, with no second decode") {
            runTest {
                val reader = CountingReader { pixels(*WAY_OF_KINGS) }
                val repository = CoverThemeRepositoryImpl(reader, decodeDispatcher = StandardTestDispatcher(testScheduler))

                repository.cached(book, "h1", IOS_STYLE) shouldBe null
                val first = repository.observe(book, "h1", IOS_STYLE).first()
                repository.cached(book, "h1", IOS_STYLE) shouldBe first
                repository.observe(book, "h1", IOS_STYLE).first() shouldBe first
                reader.requests.size shouldBe 1
            }
        }

        test("a new cover hash is a new cover: it decodes again") {
            runTest {
                val reader = CountingReader { hash -> if (hash == "h1") pixels(*WAY_OF_KINGS) else pixels(*GREEN) }
                val repository = CoverThemeRepositoryImpl(reader, decodeDispatcher = StandardTestDispatcher(testScheduler))

                val kings = repository.observe(book, "h1", IOS_STYLE).first()
                val green = repository.observe(book, "h2", IOS_STYLE).first()
                kings shouldNotBe green
                reader.requests.size shouldBe 2
            }
        }

        test("an unreadable cover is neutral and is not remembered, so the next visit tries again") {
            runTest {
                var available = false
                val reader = CountingReader { if (available) pixels(*WAY_OF_KINGS) else null }
                val repository = CoverThemeRepositoryImpl(reader, decodeDispatcher = StandardTestDispatcher(testScheduler))

                repository.observe(book, "h1", IOS_STYLE).first() shouldBe null
                available = true
                repository.observe(book, "h1", IOS_STYLE).first().shouldNotBeNull()
                reader.requests.size shouldBe 2
            }
        }

        test("a near-coral cover is remembered as neutral: no second decode") {
            runTest {
                val reader = CountingReader { pixels(*NEAR_CORAL) }
                val repository = CoverThemeRepositoryImpl(reader, decodeDispatcher = StandardTestDispatcher(testScheduler))

                repository.observe(book, "h1", IOS_STYLE).first() shouldBe null
                repository.observe(book, "h1", IOS_STYLE).first() shouldBe null
                reader.requests.size shouldBe 1
            }
        }

        test("a cover with no hash is never cached") {
            runTest {
                val reader = CountingReader { pixels(*WAY_OF_KINGS) }
                val repository = CoverThemeRepositoryImpl(reader, decodeDispatcher = StandardTestDispatcher(testScheduler))

                repository.observe(book, null, IOS_STYLE).first().shouldNotBeNull()
                repository.cached(book, null, IOS_STYLE) shouldBe null
                repository.observe(book, null, IOS_STYLE).first()
                reader.requests.size shouldBe 2
            }
        }

        test("the cache is bounded: the least recently used cover goes first") {
            runTest {
                val reader = CountingReader { pixels(*WAY_OF_KINGS) }
                val repository = CoverThemeRepositoryImpl(reader, decodeDispatcher = StandardTestDispatcher(testScheduler), capacity = 2)

                repository.observe(book, "a", IOS_STYLE).first()
                repository.observe(book, "b", IOS_STYLE).first()
                repository.observe(book, "a", IOS_STYLE).first() // touches a: b is now the oldest
                repository.observe(book, "c", IOS_STYLE).first() // evicts b

                repository.cached(book, "a", IOS_STYLE).shouldNotBeNull()
                repository.cached(book, "b", IOS_STYLE) shouldBe null
                repository.cached(book, "c", IOS_STYLE).shouldNotBeNull()
            }
        }

        test("the decode runs on the decode dispatcher, not the collector's") {
            runTest {
                val decode = StandardTestDispatcher(testScheduler)
                val reader = CountingReader { pixels(*WAY_OF_KINGS) }
                val repository = CoverThemeRepositoryImpl(reader, decodeDispatcher = decode)

                repository.observe(book, "h1", IOS_STYLE).test {
                    reader.requests.size shouldBe 0 // queued on `decode`, not run inline
                    testScheduler.advanceUntilIdle()
                    awaitItem().shouldNotBeNull()
                    awaitComplete()
                }
            }
        }
    })
