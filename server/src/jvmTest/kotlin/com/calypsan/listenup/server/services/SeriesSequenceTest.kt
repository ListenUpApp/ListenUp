package com.calypsan.listenup.server.services

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pins [parseSeriesSequence] against the SQL in `V62__series_sequence_numeric.sql`.
 *
 * These two must agree exactly, and nothing but this test says so. The migration converts the
 * sequence text already in the database; this function converts the text arriving from a scan or a
 * metadata provider afterwards. If they ever disagree, a rescan silently overwrites a value the
 * migration had preserved — the sequence would simply vanish from a book that had one, with no
 * error anywhere. Every case below is written as the pair: what SQLite's guarded CAST produces, and
 * what this function must therefore produce.
 */
class SeriesSequenceTest :
    FunSpec({

        // ── plain numbers ────────────────────────────────────────────────────

        test("a whole number parses to itself") {
            parseSeriesSequence("1") shouldBe 1.0
            parseSeriesSequence("12") shouldBe 12.0
        }

        test("a half-numbered entry keeps its fraction — the reason this is Double, not Int") {
            parseSeriesSequence("1.5") shouldBe 1.5
            parseSeriesSequence("0.5") shouldBe 0.5
        }

        test("a zero-padded number loses its padding") {
            // SQLite: CAST('06' AS REAL) = 6.0
            parseSeriesSequence("06") shouldBe 6.0
        }

        // ── the omnibus case, which is why a bare toDoubleOrNull() is wrong ──

        // `toDoubleOrNull()` returns null for every one of these, which is exactly the bug being
        // fixed: the old save path used it and silently discarded the sequence. SQLite's CAST reads
        // a leading numeric prefix instead, so the migration files an omnibus at the first book it
        // contains. This function has to do the same or the two paths disagree.
        test("an omnibus range files at the first book it contains") {
            parseSeriesSequence("1-3") shouldBe 1.0
            parseSeriesSequence("1-6") shouldBe 1.0
            parseSeriesSequence("1 Parts 1-2") shouldBe 1.0
        }

        test("a numeric prefix with a trailing suffix keeps the number") {
            // SQLite: CAST('0a' AS REAL) = 0.0
            parseSeriesSequence("0a") shouldBe 0.0
        }

        test("only the first fraction is read") {
            // SQLite: CAST('1.5.2' AS REAL) = 1.5
            parseSeriesSequence("1.5.2") shouldBe 1.5
        }

        // ── the guard: a wrong number is worse than no number ────────────────

        // A bare CAST would make these 0.0 and file an unnumbered volume as book 0 — ahead of book
        // 1, in every list, forever. Both the migration and this function refuse rather than invent.
        test("text that does not begin with a digit is refused, not coerced to zero") {
            parseSeriesSequence("Prequel") shouldBe null
            parseSeriesSequence("Book Zero") shouldBe null
            parseSeriesSequence("-1") shouldBe null
            // '.5' is refused too: SQLite's GLOB '[0-9]*' guard rejects it before CAST sees it.
            parseSeriesSequence(".5") shouldBe null
        }

        test("absent, empty and whitespace-only all mean no sequence") {
            parseSeriesSequence(null) shouldBe null
            parseSeriesSequence("") shouldBe null
            parseSeriesSequence("   ") shouldBe null
        }

        test("surrounding whitespace is trimmed, as TRIM() does in the migration") {
            parseSeriesSequence("  2  ") shouldBe 2.0
        }
    })
