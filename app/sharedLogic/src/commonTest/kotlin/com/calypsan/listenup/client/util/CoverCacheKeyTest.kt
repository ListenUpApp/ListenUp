package com.calypsan.listenup.client.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Tests for [bookCoverCacheKey] — the regression guard for the stale-cover bug: a re-covered book
 * must produce a different cache key so Coil re-fetches instead of serving the cached old image.
 */
class CoverCacheKeyTest :
    FunSpec({

        test("a changed cover hash produces a different key (busts the cache)") {
            bookCoverCacheKey("book-1", "hashA") shouldNotBe bookCoverCacheKey("book-1", "hashB")
        }

        test("same book and hash produce a stable key (cache hit across local + server sources)") {
            bookCoverCacheKey("book-1", "hashA") shouldBe bookCoverCacheKey("book-1", "hashA")
        }

        test("a null hash falls back to the legacy per-book key") {
            bookCoverCacheKey("book-1", null) shouldBe "book-1:cover"
        }

        test("different books never collide") {
            bookCoverCacheKey("book-1", "hashA") shouldNotBe bookCoverCacheKey("book-2", "hashA")
        }
    })
