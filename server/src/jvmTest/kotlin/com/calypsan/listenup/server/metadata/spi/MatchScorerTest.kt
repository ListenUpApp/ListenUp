package com.calypsan.listenup.server.metadata.spi

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

/**
 * Unit + property tests for [MatchScorer] — the pure phase-1 duration/title/author
 * scorer. Verifies the approved `0.7·duration + 0.2·title + 0.1·author` weighting, the
 * renormalize-over-present-signals degradation rule, tokenized similarity, and stable
 * best-first ranking.
 */
class MatchScorerTest :
    FunSpec({
        fun local(
            title: String = "The Way of Kings",
            author: String? = "Brandon Sanderson",
            durationMs: Long? = 36_000_000,
        ) = BookIdentity(title = title, primaryAuthor = author, durationMs = durationMs)

        fun candidate(
            title: String = "The Way of Kings",
            author: String? = "Brandon Sanderson",
            durationMs: Long? = 36_000_000,
            score: Double = 0.0,
        ) = BookMatch(title = title, author = author, durationMs = durationMs, score = score)

        test("exact title + author + duration scores 1.0") {
            MatchScorer.score(local(), candidate()) shouldBe 1.0
        }

        test("a total mismatch on every signal scores 0.0") {
            val score =
                MatchScorer.score(
                    local(title = "Mistborn", author = "Brandon Sanderson", durationMs = 36_000_000),
                    candidate(title = "War and Peace", author = "Leo Tolstoy", durationMs = 216_000_000),
                )
            score shouldBe 0.0
        }

        test("title similarity is order-independent and tolerant of subtitle noise") {
            val authorFlip =
                MatchScorer.score(
                    local(title = "x", author = "Brandon Sanderson", durationMs = null),
                    candidate(title = "x", author = "Sanderson, Brandon", durationMs = null),
                )
            authorFlip shouldBe 1.0

            // Extra subtitle tokens dilute but do not break the title match.
            val subtitled =
                MatchScorer.score(
                    local(title = "The Way of Kings", author = null, durationMs = null),
                    candidate(title = "The Way of Kings: Book One of the Stormlight Archive", author = null, durationMs = null),
                )
            subtitled shouldBeGreaterThan 0.5
            subtitled shouldBeLessThan 1.0
        }

        test("duration dominates: a runtime match outranks a title-only match") {
            val subject = local(title = "The Way of Kings", author = null, durationMs = 36_000_000)
            val runtimeMatch = candidate(title = "Utterly Different Title", author = null, durationMs = 36_000_000)
            val titleMatchWrongRuntime = candidate(title = "The Way of Kings", author = null, durationMs = 18_000_000)

            MatchScorer.score(subject, runtimeMatch) shouldBeGreaterThan MatchScorer.score(subject, titleMatchWrongRuntime)
        }

        test("degradation: a duration-only comparison still spans the full 0..1 range") {
            val subject = local(title = "", author = null, durationMs = 36_000_000)
            MatchScorer.score(subject, candidate(title = "anything", author = null, durationMs = 36_000_000)) shouldBe 1.0
            MatchScorer.score(subject, candidate(title = "anything", author = null, durationMs = 72_000_000)) shouldBe 0.0
        }

        test("no comparable signal yields 0.0") {
            val subject = local(title = "", author = null, durationMs = null)
            MatchScorer.score(subject, candidate(title = "x", author = "y", durationMs = 1_000)) shouldBe 0.0
        }

        test("a blank candidate author simply drops the author signal (no penalty)") {
            // Only title compared; identical titles → 1.0 despite the author being unknown on one side.
            MatchScorer.score(
                local(title = "Elantris", author = "Brandon Sanderson", durationMs = null),
                candidate(title = "Elantris", author = null, durationMs = null),
            ) shouldBe 1.0
        }

        test("rank returns candidates best-first with recomputed scores") {
            val subject = local(title = "The Way of Kings", author = "Brandon Sanderson", durationMs = 36_000_000)
            val ranked =
                MatchScorer.rank(
                    subject,
                    listOf(
                        candidate(title = "Wrong Book", author = "Someone Else", durationMs = 5_000_000, score = 1.0),
                        candidate(title = "The Way of Kings", author = "Brandon Sanderson", durationMs = 36_000_000, score = 0.0),
                    ),
                )
            ranked.first().title shouldBe "The Way of Kings"
            ranked.first().score shouldBe 1.0
            ranked.last().score shouldBeLessThan ranked.first().score
        }

        test("rank is stable for equal scores — source order is preserved") {
            // Neither candidate shares any signal with the subject → both score 0.0; original order holds.
            val subject = local(title = "Zzz", author = null, durationMs = null)
            val a = candidate(title = "Aaa", author = null, durationMs = null)
            val b = candidate(title = "Bbb", author = null, durationMs = null)
            val ranked = MatchScorer.rank(subject, listOf(a, b))
            ranked.map { it.title } shouldBe listOf("Aaa", "Bbb")
        }

        test("property: score is always within 0.0..1.0") {
            checkAll(
                Arb.string(maxSize = 30),
                Arb.string(maxSize = 30),
                Arb.string(maxSize = 30).orNull(),
                Arb.string(maxSize = 30).orNull(),
                Arb.long(0L..500_000_000L).orNull(),
                Arb.long(0L..500_000_000L).orNull(),
            ) { localTitle, candTitle, localAuthor, candAuthor, localDur, candDur ->
                val score =
                    MatchScorer.score(
                        BookIdentity(title = localTitle, primaryAuthor = localAuthor, durationMs = localDur),
                        BookMatch(title = candTitle, author = candAuthor, durationMs = candDur, score = 0.0),
                    )
                (score in 0.0..1.0) shouldBe true
            }
        }

        test("property: identical title/author/duration always scores 1.0 when at least one signal is present") {
            checkAll(
                Arb.string(minSize = 1, maxSize = 30).orNull(),
                Arb.long(1L..500_000_000L).orNull(),
            ) { author, duration ->
                val identity = BookIdentity(title = "Shared Title", primaryAuthor = author, durationMs = duration)
                val match = BookMatch(title = "Shared Title", author = author, durationMs = duration, score = 0.0)
                MatchScorer.score(identity, match) shouldBe 1.0
            }
        }
    })
