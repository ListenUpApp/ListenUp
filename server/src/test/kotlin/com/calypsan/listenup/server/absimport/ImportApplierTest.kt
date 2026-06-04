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
import com.calypsan.listenup.server.db.UserEntity
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.UserStatusColumn
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.services.PlaybackPositionRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
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
                    val (paths, importId, repo) = stageAnalyzedImport(db)
                    val applier = applierFor(repo, paths)

                    confirmSimonMapping(paths, importId)
                    val result = applier.apply(importId) {}

                    result.shouldBeInstanceOf<AppResult.Success<*>>()
                    val imported = (result as AppResult.Success).data

                    val finished = repo.getPosition(LU_USER, LU_KINGS).shouldNotBeNull()
                    finished.finished shouldBe true

                    val inProgress = repo.getPosition(LU_USER, LU_MIST).shouldNotBeNull()
                    inProgress.positionMs shouldBe 1_234_000L
                    inProgress.finished shouldBe false

                    imported.importedCount shouldBe 2
                    imported.perUser[UserId(LU_USER)] shouldBe 2
                }
            }
        }

        test("an unmapped ABS user's progress is skipped and counted in skippedCount") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val (paths, importId, repo) = stageAnalyzedImport(db, withExtraProgressUser = true)
                    val applier = applierFor(repo, paths)

                    confirmSimonMapping(paths, importId)
                    val result = (applier.apply(importId) {} as AppResult.Success).data

                    // simon's two books import; the extra ABS user's one row is unmapped → skipped.
                    result.importedCount shouldBe 2
                    result.skippedCount shouldBe 1
                }
            }
        }

        test("re-apply is idempotent: no duplicate rows and a newer local position is preserved") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val (paths, importId, repo) = stageAnalyzedImport(db)
                    val applier = applierFor(repo, paths)
                    confirmSimonMapping(paths, importId)

                    applier.apply(importId) {}
                    // Second apply re-fires the same recordPosition calls with the same ABS lastPlayedAt.
                    applier.apply(importId) {}

                    // Exactly one row per (user, book) — last-played-wins upsert, no duplication.
                    val mist = repo.getPosition(LU_USER, LU_MIST).shouldNotBeNull()
                    mist.positionMs shouldBe 1_234_000L

                    // A local position with a NEWER lastPlayedAt must survive a re-apply.
                    repo.recordPosition(
                        userId = LU_USER,
                        bookId = LU_MIST,
                        positionMs = 9_999_000L,
                        lastPlayedAt = FUTURE_MS,
                        finished = false,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )
                    applier.apply(importId) {}

                    val preserved = repo.getPosition(LU_USER, LU_MIST).shouldNotBeNull()
                    preserved.positionMs shouldBe 9_999_000L
                }
            }
        }

        test("a null book override skips that item — its progress is not written") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val (paths, importId, repo) = stageAnalyzedImport(db)
                    val applier = applierFor(repo, paths)

                    // Map simon, but skip book-1 (the finished item) via a null override.
                    ImportStore(paths).writeMapping(
                        importId,
                        userMappings = mapOf(AbsUserId(ABS_USER) to UserId(LU_USER)),
                        bookOverrides = mapOf(AbsItemId("book-1") to null),
                    )
                    val result = (applier.apply(importId) {} as AppResult.Success).data

                    // book-1 skipped; only book-2 (in-progress) imported.
                    repo.getPosition(LU_USER, LU_KINGS).shouldBeNull()
                    repo.getPosition(LU_USER, LU_MIST).shouldNotBeNull()
                    result.importedCount shouldBe 1
                }
            }
        }

        test("apply without a confirmed mapping returns ApplyFailed") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val (paths, importId, repo) = stageAnalyzedImport(db)
                    val applier = applierFor(repo, paths)

                    val result = applier.apply(importId) {}
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<ImportError.ApplyFailed>()
                }
            }
        }

        test("apply of a never-analyzed import returns ImportNotFound") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val home = Files.createTempDirectory("abs-apply-missing-")
                    val paths = ImportPaths(home).apply { ensureDirs() }
                    val repo = PlaybackPositionRepository(db = db, bus = ChangeBus(), registry = SyncRegistry())
                    val applier = applierFor(repo, paths)

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
)

/**
 * Stages the synthetic ABS backup, seeds the matching ListenUp library + user, and runs analyze so
 * `matches.json` exists. Apply tests then confirm a mapping and call [ImportApplier.apply].
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

    val repo = PlaybackPositionRepository(db = db, bus = ChangeBus(), registry = SyncRegistry())
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
    return StagedImport(paths, importId, repo)
}

private fun applierFor(
    repo: PlaybackPositionRepository,
    paths: ImportPaths,
): ImportApplier =
    ImportApplier(
        reader = AbsBackupReader(),
        store = ImportStore(paths),
        paths = paths,
        playbackPositionRepository = repo,
    )

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
