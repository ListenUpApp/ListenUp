package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import java.io.File

/**
 * Konsist guard preserving the **scanner/books decoupling**. The scanner
 * pipeline (`com.calypsan.listenup.server.scanner..`) discovers and analyses
 * audiobook folders on disk; it hands its `AnalyzedBook` DTOs to the books
 * domain through a port. It must never reach directly into Books-domain
 * internals — server-side book services, book tables, or the book sync
 * payload — because that coupling would let a scanner change cascade into
 * the books domain and vice versa.
 *
 * Book tables are SQLDelight tables, reached as `sql.booksQueries` with no import, so an import
 * check alone cannot see them. The rule therefore checks two things in every scanner file:
 * - no import of a Books-domain type: `server.services.Book*` (book services / persister),
 *   `server.db.sqldelight.Book*` (generated book-table row types), `api.sync.Book*` (the
 *   `BookSyncPayload` wire type);
 * - no book-table query object in the comment-stripped text. The query objects are derived from
 *   the `.sq` files (`Books.sq` → `booksQueries`), so a new book table is covered without an edit
 *   here.
 *
 * The scanner's own DTOs in `com.calypsan.listenup.api.dto.scanner` (e.g.
 * `CandidateBook`, `AnalyzedBook`) are *not* Books-domain types — they are the
 * scanner's output contract — so those imports are deliberately allowed.
 *
 * Both the scanner file set and the book-table set are floored so the rule cannot pass vacuously.
 */
class ScannerDoesNotImportBooks :
    FunSpec({

        /** Import prefixes that identify a Books-domain type. */
        val booksDomainImportPrefixes =
            listOf(
                "com.calypsan.listenup.server.services.Book",
                "com.calypsan.listenup.server.db.sqldelight.Book",
                "com.calypsan.listenup.api.sync.Book",
            )

        test("scanner package reaches no Books-domain type or book table") {
            val scannerFiles =
                Konsist
                    // Module-scoped for the same parse-cost reason as SidecarParsersAreReadOnly.
                    .scopeFromProduction("server")
                    .files
                    .filter { file -> file.path.contains("/server/scanner/") }

            withClue("scanner files discovered (51 today); fewer means the scope or the package moved") {
                scannerFiles.size shouldBeGreaterThanOrEqual MINIMUM_SCANNER_FILES
            }

            val sqlDirectory =
                File(scannerFiles.first().path.substringBefore("/server/src/") + SQL_DIRECTORY)
            val bookTableQueries =
                sqlDirectory
                    .list()
                    .orEmpty()
                    .filter { BOOK_TABLE_FILE.matches(it) }
                    .map { it.removeSuffix(".sq").replaceFirstChar(Char::lowercaseChar) + "Queries" }

            withClue("book-table .sq files under $sqlDirectory (13 today); fewer means the schema moved") {
                bookTableQueries.size shouldBeGreaterThanOrEqual MINIMUM_BOOK_TABLES
            }

            val importOffenders =
                scannerFiles.flatMap { file ->
                    file.imports
                        .filter { import -> booksDomainImportPrefixes.any { import.name.startsWith(it) } }
                        .map { "${file.path} -> ${it.name}" }
                }
            val queryOffenders =
                scannerFiles.flatMap { file ->
                    val body = stripComments(file.text)
                    bookTableQueries
                        .filter { query -> Regex("""\b$query\b""").containsMatchIn(body) }
                        .map { query -> "${file.path} -> $query" }
                }

            (importOffenders + queryOffenders).shouldBeEmpty()
        }
    })

private const val SQL_DIRECTORY = "/server/src/commonMain/sqldelight/com/calypsan/listenup/server/db/sqldelight"

/** A table owned by the books domain: `Book*.sq`, plus the scan-time genre staging table keyed by book. */
private val BOOK_TABLE_FILE = Regex("""(Book\w*|PendingBookGenres)\.sq""")

private const val MINIMUM_SCANNER_FILES = 45

private const val MINIMUM_BOOK_TABLES = 12
