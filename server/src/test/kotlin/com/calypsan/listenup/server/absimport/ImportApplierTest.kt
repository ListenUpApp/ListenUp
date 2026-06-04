package com.calypsan.listenup.server.absimport

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.ImportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.AbsItemId
import com.calypsan.listenup.core.AbsUserId
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ImportId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.BookTable
import com.calypsan.listenup.server.db.ListeningEventTable
import com.calypsan.listenup.server.db.UserEntity
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.UserStatusColumn
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.services.PlaybackPositionRepository
import com.calypsan.listenup.server.services.ListeningEventRepository
import com.calypsan.listenup.server.services.UserStatsBackfillService
import com.calypsan.listenup.server.services.UserStatsRepository
import com.calypsan.listenup.server.services.UserStatsUpdater
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files

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
            withInMemoryDatabase {
                val db = this
                runTest {
                    val staged = stageAnalyzedImport(db)
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
            withInMemoryDatabase {
                val db = this
                runTest {
                    val staged = stageAnalyzedImport(db)
                    val applier = applierFor(staged)
                    confirmSimonMapping(staged.paths, staged.importId)

                    val result = (applier.apply(staged.importId) {} as AppResult.Success).data

                    // Three resolvable book sessions (kings, mist, fidelity); unresolved + podcast skipped.
                    result.sessionsImported shouldBe 3
                    listeningEventIdsFor(db, LU_USER)
                        .shouldContainAll(listOf("abs:sess-kings", "abs:sess-mist", "abs:sess-fidelity"))
                }
            }
        }

        test("stats backfill totals listen-seconds from imported sessions and counts started/finished books") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val staged = stageAnalyzedImport(db)
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
                }
            }
        }

        test("re-apply is idempotent: sessions don't duplicate and stats are unchanged") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val staged = stageAnalyzedImport(db)
                    val applier = applierFor(staged)
                    confirmSimonMapping(staged.paths, staged.importId)

                    applier.apply(staged.importId) {}
                    applier.apply(staged.importId) {}

                    // Each ABS session id appears exactly once — stable id + append-only upsert.
                    val ids = listeningEventIdsFor(db, LU_USER)
                    ids.count { it == "abs:sess-kings" } shouldBe 1
                    ids.count { it == "abs:sess-mist" } shouldBe 1
                    ids.count { it == "abs:sess-fidelity" } shouldBe 1

                    val stats = staged.statsRepo.getForUser(LU_USER).shouldNotBeNull()
                    stats.totalSecondsAllTime shouldBe 5_460L
                }
            }
        }

        test("re-apply is idempotent: no duplicate rows and a newer local position is preserved") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val staged = stageAnalyzedImport(db)
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

        test("an unmapped ABS user's progress and an unresolved session are skipped and counted") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val staged = stageAnalyzedImport(db, withExtraProgressUser = true)
                    val applier = applierFor(staged)

                    confirmSimonMapping(staged.paths, staged.importId)
                    val result = (applier.apply(staged.importId) {} as AppResult.Success).data

                    // simon's two books import; the extra ABS user's one progress row is unmapped → skipped.
                    result.importedCount shouldBe 2
                    // sessions: kings/mist/fidelity import; sess-unresolved's book never matched → skipped.
                    result.sessionsImported shouldBe 3
                    // 1 unmapped progress row + 1 unresolved session.
                    result.skippedCount shouldBe 2
                }
            }
        }

        test("a null book override skips that item — its progress is not written") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val staged = stageAnalyzedImport(db)
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

        test("apply without a confirmed mapping returns ApplyFailed") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val staged = stageAnalyzedImport(db)
                    val applier = applierFor(staged)

                    val result = applier.apply(staged.importId) {}
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<ImportError.ApplyFailed>()
                }
            }
        }

        test("apply of a never-analyzed import returns ImportNotFound") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val staged = stageAnalyzedImport(db)
                    val applier = applierFor(staged)

                    val result = applier.apply(ImportId("never-analyzed")) {}
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<ImportError.ImportNotFound>()
                }
            }
        }

        test("validateMapping rejects two ABS users mapping to the same ListenUp user") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    seedLibraryUser(db)
                    val validator = MappingValidator(db)

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
            withInMemoryDatabase {
                val db = this
                runTest {
                    val validator = MappingValidator(db)

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
            withInMemoryDatabase {
                val db = this
                runTest {
                    seedLibraryUser(db)
                    val validator = MappingValidator(db)

                    val error =
                        validator.validateMapping(
                            userMappings = mapOf(AbsUserId("abs-a") to UserId(LU_USER)),
                            bookOverrides = mapOf(AbsItemId("book-1") to BookId("ghost-book")),
                        )
                    error.shouldBeInstanceOf<ImportError.MappingInvalid>()
                }
            }
        }

        test("validateMapping accepts a valid mapping") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val libId = seedLibraryUser(db)
                    transaction(db) { seedApplierBooks(libId.value) }
                    val validator = MappingValidator(db)

                    val error =
                        validator.validateMapping(
                            userMappings = mapOf(AbsUserId(ABS_USER) to UserId(LU_USER)),
                            bookOverrides = mapOf(AbsItemId("book-1") to BookId(LU_KINGS)),
                        )
                    error.shouldBeNull()
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
    val statsBackfill: UserStatsBackfillService,
)

/**
 * Stages the synthetic ABS backup, seeds the matching ListenUp library + user, and runs analyze so
 * `matches.json` exists. Apply tests then confirm a mapping and call [ImportApplier.apply]. The
 * playback, listening-event, and stats repositories all share [db] so the apply path and the test
 * read through the same substrate.
 */
private suspend fun stageAnalyzedImport(
    db: Database,
    withExtraProgressUser: Boolean = false,
): StagedImport {
    val home = Files.createTempDirectory("abs-apply-")
    val paths = ImportPaths(home).apply { ensureDirs() }
    val importId = ImportId("abs-apply-test")
    Files.createDirectories(paths.dirFor(importId.value))
    buildSyntheticAbsDb(paths.absDbFor(importId.value))
    if (withExtraProgressUser) addUnmappedProgressUser(paths.absDbFor(importId.value))

    val libId = seedLibraryUser(db)
    transaction(db) { seedApplierBooks(libId.value) }

    val bus = ChangeBus()
    val registry = SyncRegistry()
    val repo = PlaybackPositionRepository(db = db, bus = bus, registry = registry)
    val statsRepo = UserStatsRepository(db = db, bus = bus, registry = registry)
    val statsUpdater = UserStatsUpdater(db = db, userStatsRepo = statsRepo)
    val listeningEventRepo =
        ListeningEventRepository(db = db, bus = bus, registry = registry, userStatsUpdater = statsUpdater)
    val statsBackfill = UserStatsBackfillService(db = db, userStatsRepo = statsRepo)

    val analyzer =
        ImportAnalyzer(
            reader = AbsBackupReader(),
            store = ImportStore(paths),
            paths = paths,
            bookMatcher = BookMatcher(db),
            userMatcher = UserMatcher(),
            libraryRegistry = LibraryRegistry(db, mapOf("LISTENUP_LIBRARY_PATH" to "/lib")),
            db = db,
        )
    analyzer.analyze(importId) {}
    return StagedImport(paths, importId, repo, statsRepo, listeningEventRepo, statsBackfill)
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
        statsBackfill = staged.statsBackfill,
    )

/** Reads back the listening-event ids stored for [userId] through the shared db. */
private suspend fun listeningEventIdsFor(
    db: Database,
    userId: String,
): List<String> =
    suspendTransaction(db) {
        ListeningEventTable
            .selectAll()
            .where { ListeningEventTable.userId eq userId }
            .map { it[ListeningEventTable.id] }
    }

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
private suspend fun seedLibraryUser(db: Database): LibraryId {
    val libId = LibraryRegistry(db, mapOf("LISTENUP_LIBRARY_PATH" to "/lib")).currentLibrary()
    transaction(db) {
        UserEntity.new(LU_USER) {
            email = "simon@x.test"
            emailNormalized = "simon@x.test"
            passwordHash = "phc"
            role = UserRoleColumn.MEMBER
            displayName = "Simon"
            status = UserStatusColumn.ACTIVE
            createdAt = 1L
            updatedAt = 1L
        }
    }
    return libId
}

/** Seeds two ListenUp books matching the synthetic ABS items (kings by ASIN, mist by title). */
private fun seedApplierBooks(libraryId: String) {
    val now = 1_730_000_000_000L
    BookTable.insert {
        it[id] = LU_KINGS
        it[BookTable.libraryId] = libraryId
        it[title] = "The Way of Kings"
        it[asin] = "B00ASIN001"
        it[rootRelPath] = "Sanderson/Way of Kings"
        it[totalDuration] = 1L
        it[scannedAt] = now
        it[revision] = 1L
        it[createdAt] = now
        it[updatedAt] = now
    }
    BookTable.insert {
        it[id] = LU_MIST
        it[BookTable.libraryId] = libraryId
        it[title] = "Mistborn"
        it[rootRelPath] = "Sanderson/Mistborn-listenup"
        it[totalDuration] = 1L
        it[scannedAt] = now
        it[revision] = 1L
        it[createdAt] = now
        it[updatedAt] = now
    }
}

/**
 * Inserts an additional ABS user with one progress row, directly into the staged ABS sqlite. This
 * user is never mapped, so apply must skip its row (and count it in skippedCount).
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
