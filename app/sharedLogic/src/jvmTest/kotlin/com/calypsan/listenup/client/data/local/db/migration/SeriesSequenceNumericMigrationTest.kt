package com.calypsan.listenup.client.data.local.db.migration

import androidx.sqlite.execSQL
import com.calypsan.listenup.client.data.local.db.MIGRATION_5_6
import com.calypsan.listenup.client.test.db.createMigrationTestHelper
import com.calypsan.listenup.client.test.db.withStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Validates v5 → v6: `book_series.sequence` becomes a number.
 *
 * This is the riskiest statement in the change, because it is the only one that rewrites data a
 * user already has. Everything else is a type declaration the compiler checks; this converts every
 * existing row, in place, and gets one attempt on each device.
 *
 * The cases are taken from a real library rather than invented. Of 649 sequences on the reference
 * server, 642 were plain numbers and 7 were omnibus labels — `1-3` three times, plus `1-6`, `1-5`,
 * `1-2` and `1 Parts 1-2`. Each shape below is one of those, plus the guard cases that library
 * happens not to contain but another one will.
 *
 * The conversion must also match `V62__series_sequence_numeric.sql` on the server exactly. If the
 * two ever disagree, a device and its own server hold different numbers for the same book until
 * something forces a full resync — a silent, invisible split. `parseSeriesSequence` on the server
 * is pinned to the same table of cases for the same reason.
 */
class SeriesSequenceNumericMigrationTest :
    FunSpec({

        fun insertV5Row(
            db: androidx.sqlite.SQLiteConnection,
            bookId: String,
            sequence: String?,
        ) {
            val literal = sequence?.let { "'$it'" } ?: "NULL"
            db.execSQL(
                """
                INSERT INTO books
                    (id, libraryId, folderId, title, totalDuration, abridged, revision, hasScanWarning, createdAt, updatedAt)
                VALUES ('$bookId', 'lib1', 'folder1', 'Book $bookId', 0, 0, 1, 0, 0, 0)
                """.trimIndent(),
            )
            db.execSQL(
                "INSERT INTO book_series (bookId, seriesId, sequence) VALUES ('$bookId', 's1', $literal)",
            )
        }

        test("MIGRATION_5_6 converts plain numbers, files omnibus labels at their first book, and refuses text") {
            val helper = createMigrationTestHelper()
            try {
                val v5 = helper.createDatabase(version = 5)
                v5.execSQL(
                    "INSERT INTO series (id, name, revision, createdAt, updatedAt) " +
                        "VALUES ('s1', 'The Expanse', 1, 0, 0)",
                )

                // Plain numbers — the overwhelming majority.
                insertV5Row(v5, "whole", "1")
                insertV5Row(v5, "half", "1.5")
                insertV5Row(v5, "padded", "06")
                insertV5Row(v5, "double-digit", "10")
                // The omnibus labels, straight from the reference library.
                insertV5Row(v5, "omnibus", "1-3")
                insertV5Row(v5, "omnibus-parts", "1 Parts 1-2")
                // Absent in every spelling the text column allowed.
                insertV5Row(v5, "null", null)
                insertV5Row(v5, "empty", "")
                insertV5Row(v5, "blank", "   ")
                // The guard case: a bare CAST would make this 0.0 and file it AHEAD of book 1.
                insertV5Row(v5, "text", "Prequel")
                v5.close()

                val v6 = helper.runMigrationsAndValidate(version = 6, migrations = listOf(MIGRATION_5_6))

                fun sequenceOf(bookId: String): Double? =
                    v6.withStatement("SELECT sequence FROM book_series WHERE bookId = '$bookId'") { statement ->
                        statement.step() shouldBe true
                        if (statement.isNull(0)) null else statement.getDouble(0)
                    }

                sequenceOf("whole") shouldBe 1.0
                sequenceOf("half") shouldBe 1.5
                // "06" and "6" were two spellings of one number; only one survives.
                sequenceOf("padded") shouldBe 6.0
                // The whole point: as text this sorted before "2".
                sequenceOf("double-digit") shouldBe 10.0

                // An omnibus files at the first book it contains — SQLite's leading-prefix CAST.
                // Kotlin's toDoubleOrNull() would return null here, which is exactly the old bug.
                sequenceOf("omnibus") shouldBe 1.0
                sequenceOf("omnibus-parts") shouldBe 1.0

                sequenceOf("null") shouldBe null
                sequenceOf("empty") shouldBe null
                sequenceOf("blank") shouldBe null

                // Refused, not coerced. A wrong number outranks no number in every list forever.
                sequenceOf("text") shouldBe null
            } finally {
                helper.close()
            }
        }

        test("MIGRATION_5_6 keeps every row — a rebuilt table loses nothing") {
            val helper = createMigrationTestHelper()
            try {
                val v5 = helper.createDatabase(version = 5)
                v5.execSQL(
                    "INSERT INTO series (id, name, revision, createdAt, updatedAt) " +
                        "VALUES ('s1', 'The Expanse', 1, 0, 0)",
                )
                repeat(BOOK_COUNT) { index -> insertV5Row(v5, "book$index", "${index + 1}") }
                v5.close()

                val v6 = helper.runMigrationsAndValidate(version = 6, migrations = listOf(MIGRATION_5_6))

                // The table is DROPped and recreated, so "did every row come across" is a real
                // question, not a formality — a wrong column list in the INSERT…SELECT loses rows
                // silently rather than failing.
                v6.withStatement("SELECT COUNT(*) FROM book_series") { statement ->
                    statement.step() shouldBe true
                    statement.getLong(0) shouldBe BOOK_COUNT.toLong()
                }
            } finally {
                helper.close()
            }
        }
    })

/** Enough rows that a dropped batch would be obvious, small enough to stay fast. */
private const val BOOK_COUNT = 25
