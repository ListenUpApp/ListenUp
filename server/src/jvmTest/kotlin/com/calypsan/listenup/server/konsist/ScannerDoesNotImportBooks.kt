package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan

/**
 * Konsist guard preserving the **scanner/books decoupling**. The scanner
 * pipeline (`com.calypsan.listenup.server.scanner..`) discovers and analyses
 * audiobook folders on disk; it hands its `AnalyzedBook` DTOs to the books
 * domain through a port. It must never reach directly into Books-domain
 * internals — server-side book services, book persistence tables, or the book
 * sync payload — because that coupling would let a scanner change cascade into
 * the books domain and vice versa.
 *
 * The rule scans every file under the scanner package and asserts none import
 * a Books-domain type from:
 * - `com.calypsan.listenup.server.services.Book*` — book services / persister
 * - `com.calypsan.listenup.server.db.Book*` — book Exposed tables
 * - `com.calypsan.listenup.api.sync.Book*` — the `BookSyncPayload` wire type
 *
 * The scanner's own DTOs in `com.calypsan.listenup.api.dto.scanner` (e.g.
 * `CandidateBook`, `AnalyzedBook`) are *not* Books-domain types — they are the
 * scanner's output contract — so those imports are deliberately allowed.
 *
 * The scanner file set is asserted non-empty so the rule cannot pass vacuously.
 */
class ScannerDoesNotImportBooks :
    FunSpec({

        /** Import prefixes that identify a Books-domain type. */
        val booksDomainImportPrefixes =
            listOf(
                "com.calypsan.listenup.server.services.Book",
                "com.calypsan.listenup.server.db.Book",
                "com.calypsan.listenup.api.sync.Book",
            )

        test("scanner package does not import Books-domain types") {
            val scannerFiles =
                Konsist
                    .scopeFromProduction()
                    .files
                    .filter { file ->
                        file.path.contains("/server/scanner/")
                    }

            // Guard against a vacuous pass: the scanner package has many files.
            scannerFiles.size shouldBeGreaterThan 0

            val offenders =
                scannerFiles.flatMap { file ->
                    file.imports
                        .filter { import ->
                            booksDomainImportPrefixes.any { import.name.startsWith(it) }
                        }.map { "${file.path} -> ${it.name}" }
                }

            offenders.shouldBeEmpty()
        }
    })
