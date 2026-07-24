package com.calypsan.listenup.client.domain.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Covers [ScanProgressState.progressFraction] — the scan progress bar's value. It must advance
 * through ANALYZING (driven by booksAnalyzed/booksTotal) and stay null (indeterminate) while the
 * book total is unknown, rather than pinning at 100% the instant file-walking ends.
 */
class ScanProgressStateTest :
    FunSpec({

        fun state(
            books: Int = 0,
            booksTotal: Int = 0,
            current: Int = 0,
            filesTotal: Int = 0,
        ) = ScanProgressState(
            phase = "analyzing",
            current = current,
            total = current,
            added = 0,
            updated = 0,
            removed = 0,
            filesTotal = filesTotal,
            books = books,
            booksTotal = booksTotal,
        )

        test("progressFraction is null while the book total is unknown (WALKING/GROUPING)") {
            state(books = 0, booksTotal = 0, current = 1200, filesTotal = 0).progressFraction shouldBe null
        }

        test("progressFraction advances as books are analyzed") {
            state(books = 250, booksTotal = 1000).progressFraction shouldBe 0.25f
        }

        test("progressFraction reaches 1.0 when all candidates are analyzed") {
            state(books = 1000, booksTotal = 1000).progressFraction shouldBe 1.0f
        }

        test("progressFraction is coerced into 0..1 if books overshoots the total") {
            state(books = 1010, booksTotal = 1000).progressFraction shouldBe 1.0f
        }

        test("progressFraction does NOT pin at 100% just because files are fully walked") {
            // filesTotal == current (all files walked) but only 30% of books analyzed → 0.3, not 1.0.
            state(books = 30, booksTotal = 100, current = 1647, filesTotal = 1647).progressFraction shouldBe 0.3f
        }

        test("phaseDisplayName maps the persisting phase to 'Saving library'") {
            state(books = 10, booksTotal = 100).copy(phase = "persisting").phaseDisplayName shouldBe "Saving library"
        }

        test("savingLabel reads 'Saving N of M' from the book counts") {
            state(books = 250, booksTotal = 1000).savingLabel shouldBe "Saving 250 of 1000"
        }
    })
