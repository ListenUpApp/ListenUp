package com.calypsan.listenup.server.db

import com.lemonappdev.konsist.api.Konsist
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/** Which side of the listening / Hardcover line a `book_reads` statement is on. */
private enum class ReadsSide {
    /** Means "what the user listened to": must contain `source <> 'hardcover'`. */
    LISTENUP_ONLY,

    /** Deliberately returns Hardcover reads too; its consumer must tell them apart (the reason says how). */
    INCLUDES_HARDCOVER,

    /** Reads or writes only pulled rows: must contain `'hardcover'`. */
    HARDCOVER_ONLY,

    /** ListenUp's own insert: must not mention `'hardcover'`. */
    LISTENUP_WRITE,

    /** Acts on one row by primary key, which only a classified query supplies. */
    BY_ID,
}

/**
 * Every labelled statement in every `.sq` file that names `book_reads`, and the side it is on. A new
 * query is unclassified until someone decides — and writes down — whether pulled Hardcover reads
 * (#601 B3) belong in it. That decision is the whole point: stats, streaks, the coverage rule and the
 * push gate must leave Hardcover out; Readers must badge it.
 */
private val CLASSIFIED: Map<String, ReadsSide> =
    mapOf(
        "BookReads.insert" to ReadsSide.LISTENUP_WRITE,
        // Readers: shown, badged — SocialServiceImpl.bookReadership splits by source.
        "BookReads.finishesForBook" to ReadsSide.INCLUDES_HARDCOVER,
        "BookReads.finishesForUserBook" to ReadsSide.LISTENUP_ONLY,
        "BookReads.countDistinctFinishedSince" to ReadsSide.LISTENUP_ONLY,
        "BookReads.countForUser" to ReadsSide.LISTENUP_ONLY,
        "BookReads.latestFinishForUserBook" to ReadsSide.LISTENUP_ONLY,
        "BookReads.updateFinishedAtById" to ReadsSide.BY_ID,
        "BookReads.existsForUserBook" to ReadsSide.LISTENUP_ONLY,
        "BookReads.finishedAtForUser" to ReadsSide.LISTENUP_ONLY,
        "BookReads.insertPulled" to ReadsSide.HARDCOVER_ONLY,
        "BookReads.updatePulled" to ReadsSide.HARDCOVER_ONLY,
        "BookReads.deletePulledForUserBook" to ReadsSide.HARDCOVER_ONLY,
        "BookReads.deletePulledForUserBookExcept" to ReadsSide.HARDCOVER_ONLY,
        "BookReads.deletePulledNotSeenSince" to ReadsSide.HARDCOVER_ONLY,
        "BookReads.deletePulledForUser" to ReadsSide.HARDCOVER_ONLY,
        "BookReads.pulledForUser" to ReadsSide.HARDCOVER_ONLY,
        // Keeping a book off Hardcover (#1541): would its pulled reads leave Readers?
        "BookReads.hasPulledForUserBook" to ReadsSide.HARDCOVER_ONLY,
        // The history backfill (#1540): only the listener's own reads are history.
        "HardcoverHistory.selectUnsentHistory" to ReadsSide.LISTENUP_ONLY,
        "HardcoverHistory.countUnsentHistoryBooks" to ReadsSide.LISTENUP_ONLY,
        "HardcoverHistory.countOwnReadsThrough" to ReadsSide.LISTENUP_ONLY,
        "HardcoverHistory.readExists" to ReadsSide.BY_ID,
        "HardcoverHistory.latestOwnReadSince" to ReadsSide.LISTENUP_ONLY,
        // Keeping a book off Hardcover (#1541): the own reads finished while it was kept off.
        "HardcoverHistory.selectCatchUpHistory" to ReadsSide.LISTENUP_ONLY,
    )

/** Production files that may touch `bookReadsQueries` directly, relative to the server's commonMain kotlin root. */
private val READS_CALLERS: Set<String> =
    setOf(
        "com/calypsan/listenup/server/services/BookReadsRepository.kt",
        "com/calypsan/listenup/server/services/PublicProfileMaintainer.kt",
        "com/calypsan/listenup/server/services/UserStatsDerivation.kt",
        // The streak-day definition the derive and the milestone run share (finishedAtForUser).
        "com/calypsan/listenup/server/services/StreakRun.kt",
        "com/calypsan/listenup/server/hardcover/HardcoverPushRecorder.kt",
        "com/calypsan/listenup/server/hardcover/HardcoverPullStore.kt",
        "com/calypsan/listenup/server/hardcover/HardcoverConnectionStore.kt",
    )

private val LABEL = Regex("""^([A-Za-z][A-Za-z0-9_]*):\s*$""")
private val NAMES_BOOK_READS = Regex("""\bbook_reads\b""")

/** `File.Query` → the statement's SQL, comments stripped, for every labelled statement naming book_reads. */
private fun bookReadsStatements(): Map<String, String> {
    val root = File(Konsist.projectRootPath, "server/src/commonMain/sqldelight")
    require(root.isDirectory) { "sqldelight sources not found at $root" }
    val found = LinkedHashMap<String, String>()
    root.walkTopDown().filter { it.extension == "sq" }.forEach { file ->
        var label: String? = null
        val body = StringBuilder()

        fun flush() {
            val name = label ?: return
            val sql = body.toString()
            if (NAMES_BOOK_READS.containsMatchIn(sql)) found["${file.nameWithoutExtension}.$name"] = sql
        }
        file.readLines().forEach { line ->
            val match = LABEL.matchEntire(line.trim())
            if (match != null) {
                flush()
                label = match.groupValues[1]
                body.clear()
            } else if (!line.trimStart().startsWith("--")) {
                body.appendLine(line)
            }
        }
        flush()
    }
    return found
}

private fun ReadsSide.holdsFor(sql: String): Boolean =
    when (this) {
        ReadsSide.LISTENUP_ONLY -> "source <> 'hardcover'" in sql
        ReadsSide.HARDCOVER_ONLY -> "'hardcover'" in sql
        ReadsSide.LISTENUP_WRITE -> sql.trimStart().startsWith("INSERT", ignoreCase = true) && "'hardcover'" !in sql
        ReadsSide.INCLUDES_HARDCOVER -> "'hardcover'" !in sql
        ReadsSide.BY_ID -> Regex("""WHERE\s+id\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(sql)
    }

/** Every `book_reads` query is on a declared side of the listening / Hardcover line, and holds to it. */
class BookReadsQueriesClassifiedTest :
    FunSpec({
        val statements = bookReadsStatements()

        test("the scan finds the book_reads queries at all") {
            // Vacuity guard: a path or parser mistake must fail loudly, not pass over nothing.
            (statements.size >= CLASSIFIED.size) shouldBe true
        }

        test("every statement that names book_reads is classified") {
            withClue(
                "Classify each new book_reads query in BookReadsQueriesClassifiedTest.CLASSIFIED. Does it mean " +
                    "'what the user listened to'? Then add `source <> 'hardcover'` and mark it LISTENUP_ONLY. Should " +
                    "pulled Hardcover reads be shown? Then INCLUDES_HARDCOVER, and badge them where it's consumed.",
            ) {
                (statements.keys - CLASSIFIED.keys).shouldBeEmpty()
            }
        }

        test("no classification names a query that no longer exists") {
            (CLASSIFIED.keys - statements.keys).shouldBeEmpty()
        }

        test("each classified query holds to its side") {
            val broken =
                CLASSIFIED.filter { (key, side) -> statements[key]?.let { !side.holdsFor(it) } ?: false }.keys
            withClue("these queries don't hold to their declared side: $broken") { broken.shouldBeEmpty() }
        }

        test("only the known files call bookReadsQueries") {
            val kotlinRoot = File(Konsist.projectRootPath, "server/src/commonMain/kotlin")
            val callers =
                kotlinRoot
                    .walkTopDown()
                    .filter { it.extension == "kt" && "bookReadsQueries" in it.readText() }
                    .map { it.relativeTo(kotlinRoot).invariantSeparatorsPath }
                    .toSet()
            withClue("a new bookReadsQueries caller: decide whether it must leave pulled Hardcover reads out, then add it here") {
                callers shouldBe READS_CALLERS
            }
        }
    })
