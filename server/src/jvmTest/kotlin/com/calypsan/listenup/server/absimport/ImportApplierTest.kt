package com.calypsan.listenup.server.absimport

import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.imports.ImportEvent
import com.calypsan.listenup.api.dto.imports.ImportStatus
import com.calypsan.listenup.api.error.ImportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.core.AbsItemId
import com.calypsan.listenup.core.AbsUserId
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ImportId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.services.ActivityRepository
import com.calypsan.listenup.server.services.BookReadsRepository
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.services.ListeningEventRepository
import com.calypsan.listenup.server.services.PlaybackPositionRepository
import com.calypsan.listenup.server.services.PublicProfileMaintainer
import com.calypsan.listenup.server.services.StatsRecorder
import com.calypsan.listenup.server.services.UserStatsBackfillService
import com.calypsan.listenup.server.services.UserStatsRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.ControlFrame
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.activityRecorder
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.noOpPublicProfileMaintainer
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.nio.file.Files
import kotlinx.io.files.Path as IoPath

/**
 * The headline correctness tests for ABS apply: finished↔finished, in-progress position fidelity,
 * idempotent re-apply (last-played-wins), skip semantics, and mapping validation.
 *
 * Each test stages the synthetic ABS backup (the `simon` user with a finished book-1 and an
 * in-progress book-2 at currentTime=1234s), runs the real analyze → writeMapping → apply flow, and
 * asserts through the same [PlaybackPositionRepository.getPosition] the rest of the app reads from.
 */
class ImportApplierTest :
    FunSpec({

        test("finished ABS book becomes finished and in-progress book lands at the right position") {
            withSqlDatabase {
                val dbs = this
                runTest {
                    val staged = stageAnalyzedImport(dbs)
                    val applier = applierFor(staged)

                    confirmSimonMapping(staged.paths, staged.importId)
                    val result = applier.apply(staged.importId) {}

                    result.shouldBeInstanceOf<AppResult.Success<*>>()
                    val imported = (result as AppResult.Success).data

                    val finished = staged.repo.getPosition(LU_USER, LU_KINGS).shouldNotBeNull()
                    finished.finished shouldBe true

                    val inProgress = staged.repo.getPosition(LU_USER, LU_MIST).shouldNotBeNull()
                    inProgress.positionMs shouldBe 1_234_000L
                    inProgress.finished shouldBe false

                    imported.importedCount shouldBe 2
                    imported.perUser[UserId(LU_USER)] shouldBe 2
                }
            }
        }

        test("playback sessions import as listening events with stable abs ids") {
            withSqlDatabase {
                val dbs = this
                runTest {
                    val staged = stageAnalyzedImport(dbs)
                    val applier = applierFor(staged)
                    confirmSimonMapping(staged.paths, staged.importId)

                    val result = (applier.apply(staged.importId) {} as AppResult.Success).data

                    // Three resolvable book sessions (kings, mist, fidelity); unresolved + podcast skipped.
                    result.sessionsImported shouldBe 3
                    listeningEventIdsFor(dbs.sql, LU_USER)
                        .shouldContainAll(listOf("abs:sess-kings", "abs:sess-mist", "abs:sess-fidelity"))
                }
            }
        }

        test("imported started_book is dated before the book's earliest imported session") {
            withSqlDatabase {
                val dbs = this
                runTest {
                    val staged = stageAnalyzedImport(dbs)
                    // Real ABS data has progress.updatedAt AT/AFTER the last session; the synthetic
                    // fixture has it before. Push book-2's progress timestamp past both sessions so
                    // the feed-ordering bug is observable (apply re-reads the ABS db, so the post-stage
                    // mutation is seen).
                    java.sql.DriverManager
                        .getConnection("jdbc:sqlite:${staged.paths.absDbFor(staged.importId.value)}")
                        .use { conn ->
                            conn.createStatement().use { st ->
                                st.executeUpdate(
                                    "UPDATE mediaProgresses SET updatedAt = '2022-01-20 04:33:12.000 +00:00' " +
                                        "WHERE userId = 'user-simon' AND mediaItemId = 'book-2'",
                                )
                            }
                        }
                    val applier = applierFor(staged)
                    confirmSimonMapping(staged.paths, staged.importId)

                    applier.apply(staged.importId) {}.shouldBeInstanceOf<AppResult.Success<*>>()

                    val started =
                        ActivityRepository(db = dbs.sql)
                            .page(before = null, limit = 50)
                            .single { it.type == ActivityType.STARTED_BOOK && it.bookId == LU_MIST }
                    // Earliest imported session for book-2 (mistborn): sess-mist startedAt 2022-01-18T04:33:12.000Z.
                    val earliestSessionStart = 1_642_480_392_000L
                    // Strictly before the earliest imported session for (user, book).
                    started.occurredAt shouldBe earliestSessionStart - 1
                }
            }
        }

        test("stats backfill totals listen-seconds from imported sessions and counts started/finished books") {
            withSqlDatabase {
                val dbs = this
                runTest {
                    val staged = stageAnalyzedImport(dbs)
                    val applier = applierFor(staged)
                    confirmSimonMapping(staged.paths, staged.importId)

                    applier.apply(staged.importId) {}

                    val stats = staged.statsRepo.getForUser(LU_USER).shouldNotBeNull()
                    // 3600 + 1800 + 60 — the fidelity session contributes its timeListening, not its
                    // ~28-hour wall span (proves endedAt = startedAt + timeListening*1000).
                    stats.totalSecondsAllTime shouldBe 5_460L
                    // Distinct books across imported events: book-1 + book-2.
                    stats.booksStarted shouldBe 2
                    // book-1's finished progress position is reflected after backfill.
                    stats.booksFinished shouldBe 1
                    // Streak day-set unions the session days (sess-kings/mist/fidelity end Jan 17/18/19
                    // UTC) with imported progress: book-1's finished mediaProgress + book_reads finish
                    // land on Jan 16. Jan 16→17→18→19 is a 4-day consecutive run (Finding #20).
                    stats.longestStreakDays shouldBe 4
                }
            }
        }

        test("apply refreshes the public_profiles projection so the leaderboard, feed, and readers see imported stats") {
            withSqlDatabase {
                val dbs = this
                runTest {
                    val staged = stageAnalyzedImport(dbs)
                    val applier = applierFor(staged)
                    confirmSimonMapping(staged.paths, staged.importId)

                    applier.apply(staged.importId) {}

                    // The import backfills `user_stats`, but it must ALSO refresh the `public_profiles`
                    // projection: the leaderboard reads that projection directly, and the activity-feed
                    // and book-detail readers surfaces DROP any row whose user has no projection identity.
                    // Without the refresh the projection row is absent until a server restart rebuilds it,
                    // which is exactly the "leaderboard/feed/readers empty after import" regression.
                    val profile =
                        dbs.sql.publicProfilesQueries
                            .selectById(LU_USER)
                            .executeAsOneOrNull()
                            .shouldNotBeNull()
                    profile.books_finished shouldBe 1L
                }
            }
        }

        test("a successful apply broadcasts LibraryDataChanged so other clients reconcile live") {
            withSqlDatabase {
                val dbs = this
                runTest(UnconfinedTestDispatcher()) {
                    val staged = stageAnalyzedImport(dbs)
                    val applier = applierFor(staged)
                    confirmSimonMapping(staged.paths, staged.importId)

                    val frames = mutableListOf<ControlFrame>()
                    staged.bus
                        .subscribeControl()
                        .onEach { frames += it }
                        .launchIn(backgroundScope)
                    repeat(8) { yield() } // ensure the collector is subscribed before apply publishes

                    applier.apply(staged.importId) {}
                    repeat(8) { yield() } // let the post-apply broadcast drain to the collector

                    // The progress + session writes are firehose-suppressed, so the ONLY way other
                    // connected clients learn of them without a restart is this broadcast nudge.
                    frames.map { it.control } shouldContain SyncControl.LibraryDataChanged
                }
            }
        }

        test("a mid-burst failure after positions committed still broadcasts LibraryDataChanged") {
            withSqlDatabase {
                val dbs = this
                runTest(UnconfinedTestDispatcher()) {
                    val staged = stageAnalyzedImport(dbs)
                    val applier = applierFor(staged)
                    confirmSimonMapping(staged.paths, staged.importId)

                    val frames = mutableListOf<ControlFrame>()
                    staged.bus
                        .subscribeControl()
                        .onEach { frames += it }
                        .launchIn(backgroundScope)
                    repeat(8) { yield() }

                    // Same single-shot mid-burst injection as "an interrupted apply is detectable":
                    // the fixture's 2 progress rows are well under APPLY_EVENT_INTERVAL (50), so the
                    // FIRST Applying event is recordAll's tail tick — fired AFTER
                    // recordAllForImport already committed the position rows. Throwing there
                    // aborts recordSessions entirely while leaving a real, already-committed chunk
                    // above every client's cursor — exactly the gap the failure-path broadcast closes.
                    var thrown = false
                    val result =
                        applier.apply(staged.importId) { event ->
                            if (event is ImportEvent.Applying && !thrown) {
                                thrown = true
                                throw IllegalStateException("simulated crash mid-apply")
                            }
                        }
                    repeat(8) { yield() }

                    result.shouldBeInstanceOf<AppResult.Failure>()
                    // The position row committed before the throw — a genuine partial-progress failure.
                    staged.repo.getPosition(LU_USER, LU_KINGS).shouldNotBeNull()
                    frames.map { it.control } shouldContain SyncControl.LibraryDataChanged
                }
            }
        }

        test("re-apply is idempotent: sessions don't duplicate and stats are unchanged") {
            withSqlDatabase {
                val dbs = this
                runTest {
                    val staged = stageAnalyzedImport(dbs)
                    val applier = applierFor(staged)
                    confirmSimonMapping(staged.paths, staged.importId)

                    applier.apply(staged.importId) {}
                    applier.apply(staged.importId) {}

                    // Each ABS session id appears exactly once — stable id + append-only upsert.
                    val ids = listeningEventIdsFor(dbs.sql, LU_USER)
                    ids.count { it == "abs:sess-kings" } shouldBe 1
                    ids.count { it == "abs:sess-mist" } shouldBe 1
                    ids.count { it == "abs:sess-fidelity" } shouldBe 1

                    val stats = staged.statsRepo.getForUser(LU_USER).shouldNotBeNull()
                    stats.totalSecondsAllTime shouldBe 5_460L
                }
            }
        }

        test("re-apply is idempotent: no duplicate rows and a newer local position is preserved") {
            withSqlDatabase {
                val dbs = this
                runTest {
                    val staged = stageAnalyzedImport(dbs)
                    val applier = applierFor(staged)
                    confirmSimonMapping(staged.paths, staged.importId)

                    applier.apply(staged.importId) {}
                    // Second apply re-fires the same recordPosition calls with the same ABS lastPlayedAt.
                    applier.apply(staged.importId) {}

                    // Exactly one row per (user, book) — last-played-wins upsert, no duplication.
                    val mist = staged.repo.getPosition(LU_USER, LU_MIST).shouldNotBeNull()
                    mist.positionMs shouldBe 1_234_000L

                    // A local position with a NEWER lastPlayedAt must survive a re-apply.
                    staged.repo.recordPosition(
                        userId = LU_USER,
                        bookId = LU_MIST,
                        positionMs = 9_999_000L,
                        lastPlayedAt = FUTURE_MS,
                        finished = false,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )
                    applier.apply(staged.importId) {}

                    val preserved = staged.repo.getPosition(LU_USER, LU_MIST).shouldNotBeNull()
                    preserved.positionMs shouldBe 9_999_000L
                }
            }
        }

        test("a mapped user's book absent from the library counts as booksNotInLibrary; unmapped-user history does not") {
            withSqlDatabase {
                val dbs = this
                runTest {
                    val staged = stageAnalyzedImport(dbs, withExtraProgressUser = true)
                    val applier = applierFor(staged)

                    confirmSimonMapping(staged.paths, staged.importId)
                    val result = (applier.apply(staged.importId) {} as AppResult.Success).data

                    // simon's two in-library books import; the extra ABS user is unmapped, so nothing is
                    // imported for it — and, crucially, it does NOT inflate the "not in your library" count.
                    result.importedCount shouldBe 2
                    // sessions: kings/mist/fidelity import; sess-unresolved (book-unmatched) is simon's own
                    // session but its book isn't in the library → not imported.
                    result.sessionsImported shouldBe 3
                    // Exactly one distinct mapped-user book is absent from the library: `book-unmatched`.
                    // The unmapped user's history is a deliberate exclusion, not a "not found".
                    result.booksNotInLibrary shouldBe 1
                }
            }
        }

        test("a null book override skips that item — its progress is not written") {
            withSqlDatabase {
                val dbs = this
                runTest {
                    val staged = stageAnalyzedImport(dbs)
                    val applier = applierFor(staged)

                    // Map simon, but skip book-1 (the finished item) via a null override.
                    ImportStore(staged.paths).writeMapping(
                        staged.importId,
                        userMappings = mapOf(AbsUserId(ABS_USER) to UserId(LU_USER)),
                        bookOverrides = mapOf(AbsItemId("book-1") to null),
                    )
                    val result = (applier.apply(staged.importId) {} as AppResult.Success).data

                    // book-1 skipped; only book-2 (in-progress) imported.
                    staged.repo.getPosition(LU_USER, LU_KINGS).shouldBeNull()
                    staged.repo.getPosition(LU_USER, LU_MIST).shouldNotBeNull()
                    result.importedCount shouldBe 1
                }
            }
        }

        test("Applying events throttle to a final frame per pass carrying currentItem and the sessionsWritten tally") {
            withSqlDatabase {
                val dbs = this
                runTest {
                    val staged = stageAnalyzedImport(dbs)
                    val applier = applierFor(staged)
                    confirmSimonMapping(staged.paths, staged.importId)

                    val events = mutableListOf<com.calypsan.listenup.api.dto.imports.ImportEvent>()
                    applier.apply(staged.importId) { events += it }

                    val applyingEvents =
                        events.filterIsInstance<com.calypsan.listenup.api.dto.imports.ImportEvent.Applying>()
                    // Throttled to N=50: the fixture's 2 progress rows and 4 book sessions (podcast
                    // excluded) each emit only their final frame — one per pass.
                    applyingEvents.size shouldBe 2

                    // Every emitted frame is a final frame: done == total for its pass, currentItem non-null.
                    applyingEvents.forEach {
                        it.currentItem.shouldNotBeNull()
                        it.done shouldBe it.total
                    }

                    // The final Applying event of the sessions pass reflects the cumulative
                    // sessionsWritten count (3 resolvable sessions in the fixture).
                    applyingEvents.last().sessionsWritten shouldBe 3
                }
            }
        }

        test("ABS import records a book_reads row for the finished book (via the finish-flip)") {
            withSqlDatabase {
                val dbs = this
                runTest {
                    val staged = stageAnalyzedImport(dbs)
                    val applier = applierFor(staged)
                    confirmSimonMapping(staged.paths, staged.importId)

                    applier.apply(staged.importId) {}

                    // mp-1 (book-1, "2022-01-16 04:33:12" UTC) triggers the false→true flip;
                    // the finish-flip dates the read by lastPlayedAt = that instant in epoch ms.
                    staged.bookReads.finishesForUserBook(LU_USER, LU_KINGS) shouldBe listOf(1_642_307_592_000L)
                }
            }
        }

        test("re-applying the import does not duplicate the book_reads row") {
            withSqlDatabase {
                val dbs = this
                runTest {
                    val staged = stageAnalyzedImport(dbs)
                    val applier = applierFor(staged)
                    confirmSimonMapping(staged.paths, staged.importId)

                    applier.apply(staged.importId) {}
                    // Second apply: position already finished → lastPlayedAt-wins guard short-circuits
                    // recordPosition before reaching the flip → no second book_reads row.
                    applier.apply(staged.importId) {}

                    staged.bookReads.finishesForUserBook(LU_USER, LU_KINGS).size shouldBe 1
                }
            }
        }

        test("apply without a confirmed mapping returns ApplyFailed") {
            withSqlDatabase {
                val dbs = this
                runTest {
                    val staged = stageAnalyzedImport(dbs)
                    val applier = applierFor(staged)

                    val result = applier.apply(staged.importId) {}
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<ImportError.ApplyFailed>()
                }
            }
        }

        test("apply of a never-analyzed import returns ImportNotFound") {
            withSqlDatabase {
                val dbs = this
                runTest {
                    val staged = stageAnalyzedImport(dbs)
                    val applier = applierFor(staged)

                    val result = applier.apply(ImportId("never-analyzed")) {}
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<ImportError.ImportNotFound>()
                }
            }
        }

        test("validateMapping rejects two ABS users mapping to the same ListenUp user") {
            withSqlDatabase {
                val dbs = this
                runTest {
                    seedLibraryUser(dbs)
                    val validator = MappingValidator(dbs.sql)

                    val error =
                        validator.validateMapping(
                            userMappings =
                                mapOf(
                                    AbsUserId("abs-a") to UserId(LU_USER),
                                    AbsUserId("abs-b") to UserId(LU_USER),
                                ),
                            bookOverrides = emptyMap(),
                        )
                    error.shouldBeInstanceOf<ImportError.MappingInvalid>()
                }
            }
        }

        test("validateMapping rejects a mapping to a nonexistent user") {
            withSqlDatabase {
                val dbs = this
                runTest {
                    val validator = MappingValidator(dbs.sql)

                    val error =
                        validator.validateMapping(
                            userMappings = mapOf(AbsUserId("abs-a") to UserId("ghost")),
                            bookOverrides = emptyMap(),
                        )
                    error.shouldBeInstanceOf<ImportError.MappingInvalid>()
                }
            }
        }

        test("validateMapping rejects an override to a nonexistent book") {
            withSqlDatabase {
                val dbs = this
                runTest {
                    seedLibraryUser(dbs)
                    val validator = MappingValidator(dbs.sql)

                    val error =
                        validator.validateMapping(
                            userMappings = mapOf(AbsUserId("abs-a") to UserId(LU_USER)),
                            bookOverrides = mapOf(AbsItemId("book-1") to BookId("ghost-book")),
                        )
                    error.shouldBeInstanceOf<ImportError.MappingInvalid>()
                }
            }
        }

        test("an imported event does not change the user's home timezone") {
            withSqlDatabase {
                val dbs = this
                runTest {
                    val staged = stageAnalyzedImport(dbs)
                    val applier = applierFor(staged)
                    confirmSimonMapping(staged.paths, staged.importId)

                    // Seed the user with a non-UTC home timezone — import must not overwrite it.
                    dbs.sql.usersQueries.updateTimezone(timezone = "Europe/London", id = LU_USER)

                    applier.apply(staged.importId) {}

                    // ABS sessions carry tz="UTC"; the import path goes through
                    // ListeningEventRepository.upsert directly (never PlaybackServiceImpl),
                    // so the home timezone must remain "Europe/London".
                    val tz =
                        dbs.sql.usersQueries
                            .selectTimezoneById(LU_USER)
                            .executeAsOneOrNull()
                    tz shouldBe "Europe/London"
                }
            }
        }

        test("validateMapping accepts a valid mapping") {
            withSqlDatabase {
                val dbs = this
                runTest {
                    val libId = seedLibraryUser(dbs)
                    dbs.sql.transaction { seedApplierBooks(dbs.sql, libId.value) }
                    val validator = MappingValidator(dbs.sql)

                    val error =
                        validator.validateMapping(
                            userMappings = mapOf(AbsUserId(ABS_USER) to UserId(LU_USER)),
                            bookOverrides = mapOf(AbsItemId("book-1") to BookId(LU_KINGS)),
                        )
                    error.shouldBeNull()
                }
            }
        }

        test("an interrupted apply is detectable and a re-apply converges it") {
            withSqlDatabase {
                val dbs = this
                runTest {
                    val staged = stageAnalyzedImport(dbs)
                    val applier = applierFor(staged)
                    confirmSimonMapping(staged.paths, staged.importId)
                    val store = ImportStore(staged.paths)

                    // Inject a mid-burst failure: throw on the FIRST Applying event only. The catch
                    // path re-invokes onEvent with ImportEvent.Failed, so a throw-always callback
                    // would explode inside the catch block; the single-shot guard leaves ≥1 row
                    // committed with the burst aborted — a true partial state, no mocks.
                    var thrown = false
                    val result =
                        applier.apply(staged.importId) { event ->
                            if (event is ImportEvent.Applying && !thrown) {
                                thrown = true
                                throw IllegalStateException("simulated crash mid-apply")
                            }
                        }
                    result.shouldBeInstanceOf<AppResult.Failure>()

                    // Partial state: rows committed, but the import is not marked applied.
                    store.statusOf(staged.importId) shouldBe ImportStatus.MAPPED
                    store.hasInterruptedApply(staged.importId) shouldBe true

                    // Re-apply with a normal sink: converges.
                    val second = applier.apply(staged.importId) {}
                    second.shouldBeInstanceOf<AppResult.Success<*>>()
                    store.statusOf(staged.importId) shouldBe ImportStatus.APPLIED
                    store.hasInterruptedApply(staged.importId) shouldBe false
                    staged.repo
                        .getPosition(LU_USER, LU_KINGS)
                        .shouldNotBeNull()
                        .finished shouldBe true
                    staged.statsRepo
                        .getForUser(LU_USER)
                        .shouldNotBeNull()
                        .totalSecondsAllTime shouldBe 5_460L
                }
            }
        }

        test("a successful apply leaves no interrupted-apply marker") {
            withSqlDatabase {
                val dbs = this
                runTest {
                    val staged = stageAnalyzedImport(dbs)
                    val applier = applierFor(staged)
                    confirmSimonMapping(staged.paths, staged.importId)
                    val store = ImportStore(staged.paths)

                    applier.apply(staged.importId) {}

                    store.hasInterruptedApply(staged.importId) shouldBe false
                    store.statusOf(staged.importId) shouldBe ImportStatus.APPLIED
                }
            }
        }

        test("boot-time resume converges an interrupted apply: applied, stats, broadcast") {
            withSqlDatabase {
                val dbs = this
                runTest(UnconfinedTestDispatcher()) {
                    val staged = stageAnalyzedImport(dbs)
                    val applier = applierFor(staged)
                    confirmSimonMapping(staged.paths, staged.importId)
                    val store = ImportStore(staged.paths)

                    // Interrupt the first apply (same seam as the detection test).
                    var thrown = false
                    applier.apply(staged.importId) { event ->
                        if (event is ImportEvent.Applying && !thrown) {
                            thrown = true
                            throw IllegalStateException("boom")
                        }
                    }
                    store.hasInterruptedApply(staged.importId) shouldBe true

                    // Collect control frames like the existing LibraryDataChanged test.
                    val frames = mutableListOf<ControlFrame>()
                    staged.bus
                        .subscribeControl()
                        .onEach { frames += it }
                        .launchIn(backgroundScope)
                    repeat(8) { yield() } // ensure the collector is subscribed before resume publishes

                    val resumer =
                        InterruptedImportResumer(
                            store = store,
                            applier = applier,
                            eventBus = MutableSharedFlow(extraBufferCapacity = 64),
                        )
                    resumer.resumeAll() shouldBe listOf(staged.importId.value)
                    repeat(8) { yield() } // let the post-apply broadcast drain to the collector

                    store.statusOf(staged.importId) shouldBe ImportStatus.APPLIED
                    store.hasInterruptedApply(staged.importId) shouldBe false
                    staged.statsRepo
                        .getForUser(LU_USER)
                        .shouldNotBeNull()
                        .totalSecondsAllTime shouldBe 5_460L
                    frames.map { it.control } shouldContain SyncControl.LibraryDataChanged
                }
            }
        }

        test("resumer is a no-op when nothing was interrupted") {
            withSqlDatabase {
                val dbs = this
                runTest {
                    val staged = stageAnalyzedImport(dbs)
                    confirmSimonMapping(staged.paths, staged.importId)
                    val store = ImportStore(staged.paths)

                    // Mapped but never applied → no `.applying` marker exists to heal.
                    val resumer =
                        InterruptedImportResumer(
                            store = store,
                            applier = applierFor(staged),
                            eventBus = MutableSharedFlow(extraBufferCapacity = 64),
                        )
                    resumer.resumeAll() shouldBe emptyList<String>()
                    store.statusOf(staged.importId) shouldBe ImportStatus.MAPPED
                    staged.repo.getPosition(LU_USER, LU_KINGS).shouldBeNull()
                }
            }
        }
    })

private const val ABS_USER = "user-simon"
private const val LU_USER = "lu-simon"
private const val LU_KINGS = "lu-kings"
private const val LU_MIST = "lu-mist"
private const val FUTURE_MS = 4_000_000_000_000L

private data class StagedImport(
    val paths: ImportPaths,
    val importId: ImportId,
    val repo: PlaybackPositionRepository,
    val statsRepo: UserStatsRepository,
    val listeningEventRepo: ListeningEventRepository,
    val statsRecorder: StatsRecorder,
    val bookReads: BookReadsRepository,
    val bus: ChangeBus,
)

/**
 * Stages the synthetic ABS backup, seeds the matching ListenUp library + user, and runs analyze so
 * `matches.json` exists. Apply tests then confirm a mapping and call [ImportApplier.apply]. The
 * playback, listening-event, and stats repositories all share [dbs] so the apply path and the test
 * read through the same substrate.
 */
private suspend fun stageAnalyzedImport(
    dbs: SqlTestDatabases,
    withExtraProgressUser: Boolean = false,
): StagedImport {
    val home = Files.createTempDirectory("abs-apply-")
    val paths = ImportPaths(IoPath(home.toString())).apply { ensureDirs() }
    val importId = ImportId("abs-apply-test")
    Files.createDirectories(
        java.nio.file.Path
            .of(paths.dirFor(importId.value).toString()),
    )
    buildSyntheticAbsDb(
        java.nio.file.Path
            .of(paths.absDbFor(importId.value).toString()),
    )
    if (withExtraProgressUser) {
        addUnmappedProgressUser(
            java.nio.file.Path
                .of(paths.absDbFor(importId.value).toString()),
        )
    }

    val libId = seedLibraryUser(dbs)
    dbs.sql.transaction { seedApplierBooks(dbs.sql, libId.value) }

    val bus = ChangeBus()
    val registry = SyncRegistry()
    val bookReads = BookReadsRepository(db = dbs.sql)
    val statsRepo = UserStatsRepository(db = dbs.sql, bus = bus, registry = registry)
    val statsBackfill = UserStatsBackfillService(sql = dbs.sql, userStatsRepo = statsRepo)
    val publicProfileMaintainer = dbs.sql.noOpPublicProfileMaintainer()
    val statsRecorder =
        StatsRecorder(
            sql = dbs.sql,
            userStatsRepo = statsRepo,
            bookReadsRepository = bookReads,
            publicProfileMaintainer = publicProfileMaintainer,
            activityRecorder = dbs.activityRecorder(bus = bus),
            statsBackfill = statsBackfill,
        )
    val listeningEventRepo =
        ListeningEventRepository(db = dbs.sql, bus = bus, registry = registry, statsRecorder = statsRecorder)
    val repo = PlaybackPositionRepository(db = dbs.sql, bus = bus, registry = registry, statsRecorder = statsRecorder)

    val analyzer =
        ImportAnalyzer(
            reader = AbsBackupReader(),
            store = ImportStore(paths),
            paths = paths,
            bookMatcher = BookMatcher(dbs.sql),
            userMatcher = UserMatcher(),
            libraryRegistry = LibraryRegistry(dbs.sql),
            sql = dbs.sql,
        )
    analyzer.analyze(importId) {}
    return StagedImport(
        paths,
        importId,
        repo,
        statsRepo,
        listeningEventRepo,
        statsRecorder,
        bookReads,
        bus,
    )
}

private fun applierFor(
    staged: StagedImport,
): ImportApplier =
    ImportApplier(
        reader = AbsBackupReader(),
        store = ImportStore(staged.paths),
        paths = staged.paths,
        playbackPositionRepository = staged.repo,
        sessionConverter = SessionConverter(),
        listeningEventRepository = staged.listeningEventRepo,
        statsRecorder = staged.statsRecorder,
        changeBus = staged.bus,
    )

/** Reads back the listening-event ids stored for [userId] through the shared db. */
private fun listeningEventIdsFor(
    sql: ListenUpDatabase,
    userId: String,
): List<String> = sql.listeningEventsQueries.selectIdsByUserId(user_id = userId).executeAsList()

private suspend fun confirmSimonMapping(
    paths: ImportPaths,
    importId: ImportId,
) {
    ImportStore(paths).writeMapping(
        importId,
        userMappings = mapOf(AbsUserId(ABS_USER) to UserId(LU_USER)),
        bookOverrides = emptyMap(),
    )
}

/** Seeds the ListenUp library + the `simon` user, returning the resolved library id. */
private suspend fun seedLibraryUser(dbs: SqlTestDatabases): LibraryId {
    dbs.sql.seedTestLibraryAndFolder()
    val libId = LibraryRegistry(dbs.sql).currentLibrary()
    dbs.sql.usersQueries.insert(
        id = LU_USER,
        email = "simon@x.test",
        email_normalized = "simon@x.test",
        password_hash = "phc",
        role = "MEMBER",
        display_name = "Simon",
        status = "ACTIVE",
        created_at = 1L,
        updated_at = 1L,
        last_login_at = null,
        can_edit = 1L,
        can_share = 1L,
        approved_by = null,
        approved_at = null,
        deleted_at = null,
        invited_by = null,
        tagline = null,
        avatar_type = "auto",
        timezone = "UTC",
    )
    return libId
}

/** Seeds two ListenUp books matching the synthetic ABS items (kings by ASIN, mist by title). */
private fun seedApplierBooks(
    sql: ListenUpDatabase,
    libraryId: String,
) {
    val now = 1_730_000_000_000L
    sql.booksQueries.insert(
        id = LU_KINGS,
        library_id = libraryId,
        folder_id = "test-folder",
        title = "The Way of Kings",
        sort_title = null,
        subtitle = null,
        description = null,
        publish_year = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = "B00ASIN001",
        abridged = 0L,
        explicit = 0L,
        has_scan_warning = 0L,
        total_duration = 1L,
        cover_source = null,
        cover_path = null,
        cover_hash = null,
        field_provenance = "{}",
        root_rel_path = "Sanderson/Way of Kings",
        inode = null,
        scanned_at = now,
        revision = 1L,
        created_at = now,
        updated_at = now,
        deleted_at = null,
        client_op_id = null,
    )
    sql.booksQueries.insert(
        id = LU_MIST,
        library_id = libraryId,
        folder_id = "test-folder",
        title = "Mistborn",
        sort_title = null,
        subtitle = null,
        description = null,
        publish_year = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = null,
        abridged = 0L,
        explicit = 0L,
        has_scan_warning = 0L,
        total_duration = 1L,
        cover_source = null,
        cover_path = null,
        cover_hash = null,
        field_provenance = "{}",
        root_rel_path = "Sanderson/Mistborn-listenup",
        inode = null,
        scanned_at = now,
        revision = 1L,
        created_at = now,
        updated_at = now,
        deleted_at = null,
        client_op_id = null,
    )
}

/**
 * Inserts an additional ABS user with one progress row, directly into the staged ABS sqlite. This
 * user is never mapped, so apply imports nothing for it — and, being a deliberate exclusion rather
 * than a missing book, it must NOT inflate `booksNotInLibrary`.
 */
private fun addUnmappedProgressUser(absDb: java.nio.file.Path) {
    java.sql.DriverManager.getConnection("jdbc:sqlite:${absDb.toAbsolutePath()}").use { conn ->
        conn
            .prepareStatement(
                "INSERT INTO ${AbsSchema.USERS} (${AbsSchema.USER_ID}, ${AbsSchema.USER_USERNAME}, " +
                    "${AbsSchema.USER_EMAIL}, ${AbsSchema.USER_TYPE}) VALUES (?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, "user-stranger")
                ps.setString(2, "stranger")
                ps.setString(3, "stranger@x.test")
                ps.setString(4, "user")
                ps.executeUpdate()
            }
        conn
            .prepareStatement(
                "INSERT INTO ${AbsSchema.MEDIA_PROGRESSES} (id, ${AbsSchema.PROGRESS_USER_ID}, " +
                    "${AbsSchema.PROGRESS_MEDIA_ITEM_ID}, ${AbsSchema.PROGRESS_MEDIA_ITEM_TYPE}, " +
                    "${AbsSchema.PROGRESS_CURRENT_TIME}, ${AbsSchema.PROGRESS_DURATION}, " +
                    "${AbsSchema.PROGRESS_IS_FINISHED}, ${AbsSchema.PROGRESS_UPDATED_AT}) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, "mp-stranger")
                ps.setString(2, "user-stranger")
                ps.setString(3, "book-1")
                ps.setString(4, AbsSchema.MEDIA_TYPE_BOOK)
                ps.setDouble(5, 500.0)
                ps.setDouble(6, 5000.0)
                ps.setInt(7, 0)
                ps.setString(8, "2022-02-01T04:33:12.000Z")
                ps.executeUpdate()
            }
    }
}
