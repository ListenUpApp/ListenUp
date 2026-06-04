package com.calypsan.listenup.server.absimport

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

class AbsBackupReaderTest :
    FunSpec({
        test("reads non-guest users, audiobook items (podcasts excluded), and book progress") {
            val absDb = Files.createTempDirectory("abs-reader-").resolve(AbsSchema.DB_FILENAME)
            buildSyntheticAbsDb(absDb)

            AbsBackupReader().open(absDb).use { handle ->
                // Guests are excluded; root + user remain.
                handle.users().map { it.username } shouldContainExactlyInAnyOrder listOf("root", "simon")

                val items = handle.bookItems()
                // The podcast library item is filtered out; both books surface.
                items.map { it.title } shouldContainExactlyInAnyOrder listOf("The Way of Kings", "Mistborn")
                items.none { it.title == "Some Podcast" }.shouldBeTrue()

                val wayOfKings = items.first { it.title == "The Way of Kings" }
                wayOfKings.asin shouldBe "B00ASIN001"
                wayOfKings.isbn shouldBe "9780000000001"
                wayOfKings.relPath shouldBe "Brandon Sanderson/The Way of Kings"
                wayOfKings.authorName shouldBe "Brandon Sanderson"

                val progress = handle.progress()
                // One finished, one in-progress; the podcast-episode progress row is excluded.
                progress.map { it.itemId } shouldContainExactlyInAnyOrder listOf("book-1", "book-2")

                val finished = progress.first { it.itemId == "book-1" }
                finished.isFinished.shouldBeTrue()
                finished.userId shouldBe "user-simon"

                val inProgress = progress.first { it.itemId == "book-2" }
                inProgress.isFinished shouldBe false
                inProgress.currentTimeSeconds shouldBe 1234.0
                // duration 5000s → progress ≈ 0.2468
                inProgress.progress shouldBe 1234.0 / 5000.0
                // 2022-01-17T04:33:12.000Z → epoch millis
                inProgress.lastUpdateMs shouldBe 1_642_393_992_000L
            }
        }

        test("in-progress lastUpdateMs survives the seconds-suffix ISO form") {
            val absDb = Files.createTempDirectory("abs-reader-").resolve(AbsSchema.DB_FILENAME)
            buildSyntheticAbsDb(absDb)
            AbsBackupReader().open(absDb).use { handle ->
                val finished = handle.progress().first { it.itemId == "book-1" }
                // finished row uses the space-separated SQLite datetime form (no T / Z)
                finished.lastUpdateMs shouldBe 1_642_307_592_000L
            }
        }

        test("a non-ABS / malformed file surfaces a typed AbsReadException, not a raw exception") {
            val bad = Files.createTempDirectory("abs-bad-").resolve(AbsSchema.DB_FILENAME)
            Files.write(bad, "not a database".toByteArray())

            shouldThrow<AbsBackupReader.AbsReadException> {
                AbsBackupReader().open(bad).use { it.users() }
            }
        }

        test("open() returns a usable handle even for a valid-but-empty file path") {
            // A freshly created sqlite with no ABS tables: open succeeds (valid db), reads fail typed.
            val empty = Files.createTempDirectory("abs-empty-").resolve(AbsSchema.DB_FILENAME)
            DriverManager.getConnection("jdbc:sqlite:${empty.toAbsolutePath()}").use { it.createStatement().use {} }
            shouldThrow<AbsBackupReader.AbsReadException> {
                AbsBackupReader().open(empty).use { it.users() }
            }.shouldNotBeNull()
        }
    })

/**
 * Builds a synthetic `absdatabase.sqlite` matching [AbsSchema] exactly. Kept in lockstep with the
 * reader by referencing the same column constants in every `CREATE TABLE` and `INSERT` — if a
 * schema constant drifts, this fixture (and thus the test) breaks rather than passing vacuously.
 */
private fun buildSyntheticAbsDb(path: Path) {
    DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { conn ->
        conn.createStatement().use { st ->
            st.executeUpdate(
                "CREATE TABLE ${AbsSchema.USERS} (" +
                    "${AbsSchema.USER_ID} TEXT PRIMARY KEY, " +
                    "${AbsSchema.USER_USERNAME} TEXT, " +
                    "${AbsSchema.USER_EMAIL} TEXT, " +
                    "${AbsSchema.USER_TYPE} TEXT)",
            )
            st.executeUpdate(
                "CREATE TABLE ${AbsSchema.LIBRARY_ITEMS} (" +
                    "${AbsSchema.LIBRARY_ITEM_ID} TEXT PRIMARY KEY, " +
                    "${AbsSchema.LIBRARY_ITEM_LIBRARY_ID} TEXT, " +
                    "${AbsSchema.LIBRARY_ITEM_MEDIA_ID} TEXT, " +
                    "${AbsSchema.LIBRARY_ITEM_MEDIA_TYPE} TEXT, " +
                    "${AbsSchema.LIBRARY_ITEM_PATH} TEXT, " +
                    "${AbsSchema.LIBRARY_ITEM_REL_PATH} TEXT)",
            )
            st.executeUpdate(
                "CREATE TABLE ${AbsSchema.BOOKS} (" +
                    "${AbsSchema.BOOK_ID} TEXT PRIMARY KEY, " +
                    "${AbsSchema.BOOK_TITLE} TEXT, " +
                    "${AbsSchema.BOOK_SUBTITLE} TEXT, " +
                    "${AbsSchema.BOOK_ASIN} TEXT, " +
                    "${AbsSchema.BOOK_ISBN} TEXT)",
            )
            st.executeUpdate(
                "CREATE TABLE ${AbsSchema.AUTHORS} (" +
                    "${AbsSchema.AUTHOR_ID} TEXT PRIMARY KEY, " +
                    "${AbsSchema.AUTHOR_NAME} TEXT)",
            )
            st.executeUpdate(
                "CREATE TABLE ${AbsSchema.BOOK_AUTHORS} (" +
                    "${AbsSchema.BOOK_AUTHOR_BOOK_ID} TEXT, " +
                    "${AbsSchema.BOOK_AUTHOR_AUTHOR_ID} TEXT)",
            )
            st.executeUpdate(
                "CREATE TABLE ${AbsSchema.MEDIA_PROGRESSES} (" +
                    "id TEXT PRIMARY KEY, " +
                    "${AbsSchema.PROGRESS_USER_ID} TEXT, " +
                    "${AbsSchema.PROGRESS_MEDIA_ITEM_ID} TEXT, " +
                    "${AbsSchema.PROGRESS_MEDIA_ITEM_TYPE} TEXT, " +
                    "${AbsSchema.PROGRESS_CURRENT_TIME} REAL, " +
                    "${AbsSchema.PROGRESS_DURATION} REAL, " +
                    "${AbsSchema.PROGRESS_IS_FINISHED} INTEGER, " +
                    "${AbsSchema.PROGRESS_UPDATED_AT} TEXT)",
            )
        }

        // Users: root, a normal user, and a guest (excluded).
        conn
            .prepareStatement(
                "INSERT INTO ${AbsSchema.USERS} (${AbsSchema.USER_ID}, ${AbsSchema.USER_USERNAME}, " +
                    "${AbsSchema.USER_EMAIL}, ${AbsSchema.USER_TYPE}) VALUES (?, ?, ?, ?)",
            ).use { ps ->
                insertUser(ps, "user-root", "root", "root@x.test", "root")
                insertUser(ps, "user-simon", "simon", "simon@x.test", "user")
                insertUser(ps, "user-guest", "wanderer", null, AbsSchema.USER_TYPE_GUEST)
            }

        // Author.
        conn
            .prepareStatement(
                "INSERT INTO ${AbsSchema.AUTHORS} (${AbsSchema.AUTHOR_ID}, ${AbsSchema.AUTHOR_NAME}) VALUES (?, ?)",
            ).use { ps ->
                ps.setString(1, "author-1")
                ps.setString(2, "Brandon Sanderson")
                ps.executeUpdate()
            }

        // Books.
        conn
            .prepareStatement(
                "INSERT INTO ${AbsSchema.BOOKS} (${AbsSchema.BOOK_ID}, ${AbsSchema.BOOK_TITLE}, " +
                    "${AbsSchema.BOOK_SUBTITLE}, ${AbsSchema.BOOK_ASIN}, ${AbsSchema.BOOK_ISBN}) VALUES (?, ?, ?, ?, ?)",
            ).use { ps ->
                insertBook(ps, "book-1", "The Way of Kings", "B00ASIN001", "9780000000001")
                insertBook(ps, "book-2", "Mistborn", null, null)
            }

        // book-1 has an author; book-2 deliberately has none (authorName null).
        conn
            .prepareStatement(
                "INSERT INTO ${AbsSchema.BOOK_AUTHORS} (${AbsSchema.BOOK_AUTHOR_BOOK_ID}, " +
                    "${AbsSchema.BOOK_AUTHOR_AUTHOR_ID}) VALUES (?, ?)",
            ).use { ps ->
                ps.setString(1, "book-1")
                ps.setString(2, "author-1")
                ps.executeUpdate()
            }

        // Library items: two books + one podcast (excluded).
        conn
            .prepareStatement(
                "INSERT INTO ${AbsSchema.LIBRARY_ITEMS} (${AbsSchema.LIBRARY_ITEM_ID}, " +
                    "${AbsSchema.LIBRARY_ITEM_LIBRARY_ID}, ${AbsSchema.LIBRARY_ITEM_MEDIA_ID}, " +
                    "${AbsSchema.LIBRARY_ITEM_MEDIA_TYPE}, ${AbsSchema.LIBRARY_ITEM_PATH}, " +
                    "${AbsSchema.LIBRARY_ITEM_REL_PATH}) VALUES (?, ?, ?, ?, ?, ?)",
            ).use { ps ->
                insertItem(ps, "li-1", "book-1", AbsSchema.MEDIA_TYPE_BOOK, "Brandon Sanderson/The Way of Kings")
                insertItem(ps, "li-2", "book-2", AbsSchema.MEDIA_TYPE_BOOK, "Brandon Sanderson/Mistborn")
                insertItem(ps, "li-3", "podcast-1", "podcast", "Podcasts/Some Podcast")
            }

        // Progress: one finished (book-1, space-separated datetime), one in-progress (book-2, ISO-Z),
        // plus a podcast-episode row that must be excluded.
        conn
            .prepareStatement(
                "INSERT INTO ${AbsSchema.MEDIA_PROGRESSES} (id, ${AbsSchema.PROGRESS_USER_ID}, " +
                    "${AbsSchema.PROGRESS_MEDIA_ITEM_ID}, ${AbsSchema.PROGRESS_MEDIA_ITEM_TYPE}, " +
                    "${AbsSchema.PROGRESS_CURRENT_TIME}, ${AbsSchema.PROGRESS_DURATION}, " +
                    "${AbsSchema.PROGRESS_IS_FINISHED}, ${AbsSchema.PROGRESS_UPDATED_AT}) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { ps ->
                // book-1 finished, space-separated SQLite datetime; book-2 in-progress, ISO-Z;
                // episode-9 is a podcast-episode row that must be excluded.
                listOf(
                    ProgressRow("mp-1", "book-1", AbsSchema.MEDIA_TYPE_BOOK, 5000.0, 5000.0, finished = true, "2022-01-16 04:33:12"),
                    ProgressRow("mp-2", "book-2", AbsSchema.MEDIA_TYPE_BOOK, 1234.0, 5000.0, finished = false, "2022-01-17T04:33:12.000Z"),
                    ProgressRow("mp-3", "episode-9", "podcastEpisode", 10.0, 100.0, finished = false, "2022-01-18T04:33:12.000Z"),
                ).forEach { insertProgress(ps, it) }
            }
    }
}

private data class ProgressRow(
    val id: String,
    val itemId: String,
    val mediaItemType: String,
    val currentTime: Double,
    val duration: Double,
    val finished: Boolean,
    val updatedAt: String,
)

private fun insertUser(
    ps: java.sql.PreparedStatement,
    id: String,
    username: String,
    email: String?,
    type: String,
) {
    ps.setString(1, id)
    ps.setString(2, username)
    ps.setString(3, email)
    ps.setString(4, type)
    ps.executeUpdate()
}

private fun insertBook(
    ps: java.sql.PreparedStatement,
    id: String,
    title: String,
    asin: String?,
    isbn: String?,
) {
    ps.setString(1, id)
    ps.setString(2, title)
    ps.setString(3, null)
    ps.setString(4, asin)
    ps.setString(5, isbn)
    ps.executeUpdate()
}

private fun insertItem(
    ps: java.sql.PreparedStatement,
    id: String,
    mediaId: String,
    mediaType: String,
    relPath: String,
) {
    ps.setString(1, id)
    ps.setString(2, "lib-1")
    ps.setString(3, mediaId)
    ps.setString(4, mediaType)
    ps.setString(5, "/abs/$relPath")
    ps.setString(6, relPath)
    ps.executeUpdate()
}

private fun insertProgress(
    ps: java.sql.PreparedStatement,
    row: ProgressRow,
) {
    ps.setString(1, row.id)
    ps.setString(2, "user-simon")
    ps.setString(3, row.itemId)
    ps.setString(4, row.mediaItemType)
    ps.setDouble(5, row.currentTime)
    ps.setDouble(6, row.duration)
    ps.setInt(7, if (row.finished) 1 else 0)
    ps.setString(8, row.updatedAt)
    ps.executeUpdate()
}
