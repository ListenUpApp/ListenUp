package com.calypsan.listenup.server.metadata.spi

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
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
 * scorer. Verifies the `0.55·duration + 0.2·title + 0.1·author + 0.15·narrator` weighting, the
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

        context("narrators (matching redesign)") {
            fun withNarrators(
                local: List<String>,
                theirs: List<String>,
                durationMs: Long? = 36_000_000,
            ) = MatchScorer.score(
                BookIdentity(
                    title = "The Way of Kings",
                    primaryAuthor = "Brandon Sanderson",
                    durationMs = 36_000_000,
                    narrators = local,
                ),
                BookMatch(
                    title = "The Way of Kings",
                    author = "Brandon Sanderson",
                    durationMs = durationMs,
                    narrators = theirs,
                    score = 0.0,
                ),
            )

            test("the same narrators, in any order and name form, score as a full match") {
                withNarrators(listOf("Kate Reading", "Michael Kramer"), listOf("Kramer, Michael", "Kate Reading")) shouldBe 1.0
            }

            test("different narrators cost exactly the narrator weight") {
                withNarrators(listOf("Kate Reading"), listOf("Full Cast")) shouldBe (0.85 plusOrMinus 1e-9)
            }

            test("no narrators on one side drops the signal rather than penalising it") {
                withNarrators(emptyList(), listOf("Full Cast")) shouldBe 1.0
            }

            test("duration still dominates: same length with other narrators beats the narrator at half the length") {
                withNarrators(listOf("Kate Reading"), listOf("Someone Else")) shouldBeGreaterThan
                    withNarrators(listOf("Kate Reading"), listOf("Kate Reading"), durationMs = 18_000_000)
            }

            test("property: the score never rises as the length moves further from yours") {
                checkAll(Arb.long(0L..36_000_000L), Arb.long(0L..36_000_000L)) { a, b ->
                    val near = minOf(a, b)
                    val far = maxOf(a, b)

                    fun at(gap: Long) =
                        MatchScorer.score(
                            BookIdentity(title = "T", primaryAuthor = "A", durationMs = 36_000_000, narrators = listOf("N")),
                            BookMatch(
                                title = "T",
                                author = "A",
                                durationMs = 36_000_000 + gap,
                                narrators = listOf("N"),
                                score = 0.0,
                            ),
                        )
                    (at(near) >= at(far)) shouldBe true
                }
            }

            test("sameNames compares name sets, ignoring order, case and 'Last, First'") {
                MatchScorer.sameNames(listOf("Ray Porter"), listOf("porter, ray")) shouldBe true
                MatchScorer.sameNames(listOf("Ray Porter"), listOf("Ray Porter", "Full Cast")) shouldBe false
                MatchScorer.sameNames(emptyList(), emptyList()) shouldBe false
            }

            test("sameText ignores case, punctuation and word order, but not extra words") {
                MatchScorer.sameText("Project Hail Mary", "project hail-mary") shouldBe true
                MatchScorer.sameText("Project Hail Mary", "Project Hail Mary [Dramatized Adaptation]") shouldBe false
            }
        }

        context("isConfidentRatingMatch") {
            fun confident(
                localTitle: String,
                localAuthor: String?,
                candidateTitle: String,
                candidateAuthor: String?,
            ) = MatchScorer.isConfidentRatingMatch(
                BookIdentity(title = localTitle, primaryAuthor = localAuthor),
                BookMatch(title = candidateTitle, author = candidateAuthor, score = 0.0),
            )

            test("an exact title and author is a confident rating match") {
                confident(
                    "The Best Christmas Pageant Ever",
                    "Barbara Robinson",
                    "The Best Christmas Pageant Ever",
                    "Barbara Robinson",
                ) shouldBe true
            }

            test("the same title by a different author is not") {
                confident("The Little Prince", "Antoine de Saint-Exupéry", "The Little Prince", "Some Study Guide") shouldBe false
            }

            test("a different book with a shared word is not") {
                confident("The Pursuit of God", "A. W. Tozer", "The Pursuit of Happiness", "A. W. Tozer") shouldBe false
            }

            test("a subtitle on one side only is still the same book") {
                confident("Project Hail Mary", "Andy Weir", "Project Hail Mary: A Novel", "Andy Weir") shouldBe true
                confident("Project Hail Mary: A Novel", "Andy Weir", "Project Hail Mary", "Andy Weir") shouldBe true
            }

            test("two different subtitles on the same series stem are different books") {
                confident("Dune: Messiah", "Frank Herbert", "Dune: Children of Dune", "Frank Herbert") shouldBe false
            }

            test("accents and hyphens alone do not break a match") {
                confident("The Little Prince", "Antoine de Saint-Exupéry", "The Little Prince", "Antoine de Saint Exupery") shouldBe true
            }

            test("a missing author on either side is never confident") {
                confident("The Little Prince", null, "The Little Prince", "Antoine de Saint-Exupéry") shouldBe false
                confident("The Little Prince", "Antoine de Saint-Exupéry", "The Little Prince", null) shouldBe false
            }

            test("runtime is ignored") {
                MatchScorer.isConfidentRatingMatch(
                    BookIdentity(title = "Dune", primaryAuthor = "Frank Herbert", durationMs = 3_600_000),
                    BookMatch(title = "Dune", author = "Frank Herbert", durationMs = 90_000_000, score = 0.0),
                ) shouldBe true
            }
        }
    })
