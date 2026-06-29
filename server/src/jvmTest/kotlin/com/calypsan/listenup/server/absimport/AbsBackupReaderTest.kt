package com.calypsan.listenup.server.absimport

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.sql.DriverManager

class AbsBackupReaderTest :
    FunSpec({
        test("reads non-guest users, audiobook items (podcasts excluded), and book progress") {
            val absDb = Files.createTempDirectory("abs-reader-").resolve(AbsSchema.DB_FILENAME)
            buildSyntheticAbsDb(absDb)

            AbsBackupReader().open(absDb.toAbsolutePath().toString()).use { handle ->
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
                // Real ABS offset form "2022-01-17 04:33:12.000 +00:00" → epoch millis.
                inProgress.lastUpdateMs shouldBe 1_642_393_992_000L
            }
        }

        test("in-progress lastUpdateMs survives the seconds-suffix ISO form") {
            val absDb = Files.createTempDirectory("abs-reader-").resolve(AbsSchema.DB_FILENAME)
            buildSyntheticAbsDb(absDb)
            AbsBackupReader().open(absDb.toAbsolutePath().toString()).use { handle ->
                val finished = handle.progress().first { it.itemId == "book-1" }
                // finished row uses the space-separated SQLite datetime form (no T / Z)
                finished.lastUpdateMs shouldBe 1_642_307_592_000L
            }
        }

        test("playbackSessions() returns book sessions (podcasts excluded) with parsed fields") {
            val absDb = Files.createTempDirectory("abs-sessions-").resolve(AbsSchema.DB_FILENAME)
            buildSyntheticAbsDb(absDb)

            AbsBackupReader().open(absDb.toAbsolutePath().toString()).use { handle ->
                val sessions = handle.playbackSessions()
                // The podcast session is filtered out; only the book session surfaces.
                sessions.none { it.itemId == "podcast-1" }.shouldBeTrue()

                val session = sessions.single { it.itemId == "book-1" }
                session.id shouldBe "sess-kings"
                session.userId shouldBe "user-simon"
                session.timeListeningSeconds shouldBe 3600.0
                session.startPositionSeconds shouldBe 100.0
                session.endPositionSeconds shouldBe 3700.0
                // ABS stores no per-session playback rate → default 1.0.
                session.playbackSpeed shouldBe 1.0f
                session.deviceLabel shouldBe "Pixel 8"
                // createdAt is the real ABS offset form "2022-01-17 04:33:12.000 +00:00" → epoch millis.
                session.startedAtMs shouldBe 1_642_393_992_000L
            }
        }

        test("real ABS offset timestamps survive to true epochs, not 1970 (the #532 stats path)") {
            // Regression: imported listening stats stayed empty because session/progress timestamps in
            // ABS's "2024-06-12 02:48:10.063 +00:00" form parsed to 0L → every listening_event landed
            // in 1970, outside the weekly window, with all streak dates collapsed onto one day. Guard
            // both the session (startedAt → stats minutes/streaks) and progress (sort key) paths.
            val absDb = Files.createTempDirectory("abs-offset-").resolve(AbsSchema.DB_FILENAME)
            buildSyntheticAbsDb(absDb)
            AbsBackupReader().open(absDb.toAbsolutePath().toString()).use { handle ->
                val sessionStartedAt = handle.playbackSessions().single { it.itemId == "book-1" }.startedAtMs
                val progressUpdatedAt = handle.progress().first { it.itemId == "book-2" }.lastUpdateMs
                // Both sit well after the epoch — the offset form no longer collapses to 1970.
                (sessionStartedAt > 1_600_000_000_000L).shouldBeTrue()
                (progressUpdatedAt > 1_600_000_000_000L).shouldBeTrue()
            }
        }

        test("a non-ABS / malformed file surfaces a typed AbsReadException, not a raw exception") {
            val bad = Files.createTempDirectory("abs-bad-").resolve(AbsSchema.DB_FILENAME)
            Files.write(bad, "not a database".toByteArray())

            shouldThrow<AbsBackupReader.AbsReadException> {
                AbsBackupReader().open(bad.toAbsolutePath().toString()).use { it.users() }
            }
        }

        test("open() returns a usable handle even for a valid-but-empty file path") {
            // A freshly created sqlite with no ABS tables: open succeeds (valid db), reads fail typed.
            val empty = Files.createTempDirectory("abs-empty-").resolve(AbsSchema.DB_FILENAME)
            DriverManager.getConnection("jdbc:sqlite:${empty.toAbsolutePath()}").use { it.createStatement().use {} }
            shouldThrow<AbsBackupReader.AbsReadException> {
                AbsBackupReader().open(empty.toAbsolutePath().toString()).use { it.users() }
            }.shouldNotBeNull()
        }
    })
