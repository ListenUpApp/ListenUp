package com.calypsan.listenup.server.absimport

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds a synthetic `absdatabase.sqlite` matching [AbsSchema] exactly. Kept in lockstep with the
 * reader by referencing the same column constants in every `CREATE TABLE` and `INSERT` — if a
 * schema constant drifts, this fixture (and thus the dependent tests) breaks rather than passing
 * vacuously.
 *
 * Shared by [AbsBackupReaderTest] and the ABS-import route tests so both exercise the same canonical
 * ABS-shaped database.
 */
internal fun buildSyntheticAbsDb(path: Path) {
    DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { conn ->
        conn.createAbsTables()
        conn.insertAbsRows()
    }
}

private fun java.sql.Connection.createAbsTables() {
    createStatement().use { st ->
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
        st.executeUpdate(
            "CREATE TABLE ${AbsSchema.PLAYBACK_SESSIONS} (" +
                "${AbsSchema.SESSION_ID} TEXT PRIMARY KEY, " +
                "${AbsSchema.SESSION_USER_ID} TEXT, " +
                "${AbsSchema.SESSION_MEDIA_ITEM_ID} TEXT, " +
                "${AbsSchema.SESSION_MEDIA_ITEM_TYPE} TEXT, " +
                "${AbsSchema.SESSION_START_TIME} REAL, " +
                "${AbsSchema.SESSION_CURRENT_TIME} REAL, " +
                "${AbsSchema.SESSION_TIME_LISTENING} INTEGER, " +
                "${AbsSchema.SESSION_STARTED_AT} TEXT, " +
                "${AbsSchema.SESSION_DEVICE} TEXT)",
        )
    }
}

private fun java.sql.Connection.insertAbsRows() {
    val conn = this
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
            listOf(
                ProgressRow(
                    "mp-1",
                    "book-1",
                    AbsSchema.MEDIA_TYPE_BOOK,
                    5000.0,
                    5000.0,
                    finished = true,
                    "2022-01-16 04:33:12",
                ),
                ProgressRow(
                    "mp-2",
                    "book-2",
                    AbsSchema.MEDIA_TYPE_BOOK,
                    1234.0,
                    5000.0,
                    finished = false,
                    // Real Sequelize-SQLite offset form — same instant as the clean ISO-Z form.
                    "2022-01-17 04:33:12.000 +00:00",
                ),
                ProgressRow(
                    "mp-3",
                    "episode-9",
                    "podcastEpisode",
                    10.0,
                    100.0,
                    finished = false,
                    "2022-01-18T04:33:12.000Z",
                ),
            ).forEach { insertProgress(ps, it) }
        }

    insertAbsSessions()
}

/**
 * Playback sessions for `user-simon`:
 *  - `sess-kings` (book-1, `timeListening = 3600`) and `sess-mist` (book-2, `timeListening = 1800`) —
 *    the two resolvable book sessions.
 *  - `sess-fidelity` (book-2, `timeListening = 60` but a ~28-hour wall span) — proves listen-seconds
 *    follow `timeListening`, not the wall clock.
 *  - `sess-unresolved` (book-unmatched) — its item never matches a ListenUp book → skipped on apply.
 *  - `sess-podcast` (podcastEpisode) — excluded by the reader's media-type filter.
 */
private fun java.sql.Connection.insertAbsSessions() {
    prepareStatement(
        "INSERT INTO ${AbsSchema.PLAYBACK_SESSIONS} (${AbsSchema.SESSION_ID}, " +
            "${AbsSchema.SESSION_USER_ID}, ${AbsSchema.SESSION_MEDIA_ITEM_ID}, " +
            "${AbsSchema.SESSION_MEDIA_ITEM_TYPE}, ${AbsSchema.SESSION_START_TIME}, " +
            "${AbsSchema.SESSION_CURRENT_TIME}, ${AbsSchema.SESSION_TIME_LISTENING}, " +
            "${AbsSchema.SESSION_STARTED_AT}, ${AbsSchema.SESSION_DEVICE}) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
    ).use { ps ->
        listOf(
            SessionRow(
                "sess-kings",
                "user-simon",
                "book-1",
                AbsSchema.MEDIA_TYPE_BOOK,
                startTime = 100.0,
                currentTime = 3700.0,
                timeListening = 3600,
                // The real Sequelize-on-SQLite DATE form ABS writes: same instant as
                // 2022-01-17T04:33:12.000Z, but the offset spelling collapsed to 1970 in the old parser.
                startedAt = "2022-01-17 04:33:12.000 +00:00",
                device = "Pixel 8",
            ),
            SessionRow(
                "sess-mist",
                "user-simon",
                "book-2",
                AbsSchema.MEDIA_TYPE_BOOK,
                startTime = 0.0,
                currentTime = 1800.0,
                timeListening = 1800,
                startedAt = "2022-01-18T04:33:12.000Z",
                device = "Pixel 8",
            ),
            // Wall span ~28h (currentTime far past timeListening) but only 60s actually listened.
            SessionRow(
                "sess-fidelity",
                "user-simon",
                "book-2",
                AbsSchema.MEDIA_TYPE_BOOK,
                startTime = 1800.0,
                currentTime = 102_000.0,
                timeListening = 60,
                startedAt = "2022-01-19T04:33:12.000Z",
                device = "Web",
            ),
            SessionRow(
                "sess-unresolved",
                "user-simon",
                "book-unmatched",
                AbsSchema.MEDIA_TYPE_BOOK,
                startTime = 0.0,
                currentTime = 30.0,
                timeListening = 30,
                startedAt = "2022-01-20T04:33:12.000Z",
                device = "Web",
            ),
            SessionRow(
                "sess-podcast",
                "user-simon",
                "podcast-1",
                "podcastEpisode",
                startTime = 0.0,
                currentTime = 120.0,
                timeListening = 120,
                startedAt = "2022-01-21T04:33:12.000Z",
                device = "Web",
            ),
        ).forEach { insertSession(ps, it) }
    }
}

/**
 * Builds an in-memory `.audiobookshelf` zip whose only entry is a synthetic `absdatabase.sqlite`
 * (see [buildSyntheticAbsDb]). Returns the raw zip bytes, ready to upload via multipart.
 */
internal fun buildSyntheticAbsBackupZip(): ByteArray {
    val tmpDb = Files.createTempDirectory("abs-zip-").resolve(AbsSchema.DB_FILENAME)
    try {
        buildSyntheticAbsDb(tmpDb)
        val dbBytes = Files.readAllBytes(tmpDb)
        return zipOf(AbsSchema.DB_FILENAME to dbBytes)
    } finally {
        Files.deleteIfExists(tmpDb)
        Files.deleteIfExists(tmpDb.parent)
    }
}

/** Builds an in-memory zip from the given (entry-name → bytes) pairs. */
internal fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
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

private data class SessionRow(
    val id: String,
    val userId: String,
    val itemId: String,
    val mediaItemType: String,
    val startTime: Double,
    val currentTime: Double,
    val timeListening: Long,
    val startedAt: String,
    val device: String?,
)

private fun insertUser(
    ps: PreparedStatement,
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
    ps: PreparedStatement,
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
    ps: PreparedStatement,
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
    ps: PreparedStatement,
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

private fun insertSession(
    ps: PreparedStatement,
    row: SessionRow,
) {
    ps.setString(1, row.id)
    ps.setString(2, row.userId)
    ps.setString(3, row.itemId)
    ps.setString(4, row.mediaItemType)
    ps.setDouble(5, row.startTime)
    ps.setDouble(6, row.currentTime)
    ps.setLong(7, row.timeListening)
    ps.setString(8, row.startedAt)
    ps.setString(9, row.device)
    ps.executeUpdate()
}
