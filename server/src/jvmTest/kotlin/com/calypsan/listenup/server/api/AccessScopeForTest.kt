package com.calypsan.listenup.server.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Pins the pure [accessScopeFor] threshold: a small change yields a scoped delta; a change over
 * [DELTA_MAX_BOOKS] entities degrades to `null` (coarse). The null is the never-silent fallback — an
 * emission site that produces it consciously hands the client a whole-library re-derive rather than
 * an oversized targeted fetch.
 */
class AccessScopeForTest :
    FunSpec({

        test("a small change yields a scoped, de-duplicated delta") {
            val scope =
                accessScopeFor(
                    collectionIds = listOf("c1", "c1", "c2"),
                    bookIds = listOf("b1", "b2"),
                )
            scope.shouldNotBeNull()
            scope.collectionIds shouldContainExactlyInAnyOrder listOf("c1", "c2")
            scope.bookIds shouldContainExactlyInAnyOrder listOf("b1", "b2")
        }

        test("an empty change is a valid (non-null) empty scope") {
            val scope = accessScopeFor(collectionIds = emptyList(), bookIds = emptyList())
            scope.shouldNotBeNull()
            scope.collectionIds shouldBe emptyList()
            scope.bookIds shouldBe emptyList()
        }

        test("more than DELTA_MAX_BOOKS affected books degrades to coarse (null)") {
            val scope =
                accessScopeFor(
                    collectionIds = listOf("c1"),
                    bookIds = (1..DELTA_MAX_BOOKS + 1).map { "b$it" },
                )
            scope shouldBe null
        }

        test("more than DELTA_MAX_BOOKS affected collections degrades to coarse (null)") {
            val scope =
                accessScopeFor(
                    collectionIds = (1..DELTA_MAX_BOOKS + 1).map { "c$it" },
                    bookIds = listOf("b1"),
                )
            scope shouldBe null
        }

        test("exactly DELTA_MAX_BOOKS books is still a scoped delta") {
            val scope =
                accessScopeFor(
                    collectionIds = listOf("c1"),
                    bookIds = (1..DELTA_MAX_BOOKS).map { "b$it" },
                )
            scope.shouldNotBeNull()
            scope.bookIds.size shouldBe DELTA_MAX_BOOKS
        }
    })
