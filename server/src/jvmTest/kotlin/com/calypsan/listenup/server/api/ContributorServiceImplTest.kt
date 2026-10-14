@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import io.kotest.matchers.nulls.shouldBeNull
import com.calypsan.listenup.api.metadata.FieldSourceKind
import com.calypsan.listenup.api.metadata.ContributorField
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.dto.image.ImageFraming
import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.api.dto.ContributorUpdate
import com.calypsan.listenup.api.error.ContributorError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class ContributorServiceImplTest :
    FunSpec({

        test("getContributor returns Success with the payload for an existing contributor") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                runTest {
                    val id = contributorRepo.resolveOrCreate("Brandon Sanderson", sortName = null)

                    val result = service.getContributor(id)

                    val success = result.shouldBeInstanceOf<AppResult.Success<ContributorSyncPayload?>>()
                    success.data shouldNotBe null
                    success.data!!.id shouldBe id.value
                    success.data!!.name shouldBe "Brandon Sanderson"
                }
            }
        }

        test("getContributor returns AppResult.Success(null) for a non-existent contributor id") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val service = makeServiceAndDeps(db).service
                runTest {
                    val result = service.getContributor(ContributorId("does-not-exist"))

                    val success = result.shouldBeInstanceOf<AppResult.Success<ContributorSyncPayload?>>()
                    success.data shouldBe null
                }
            }
        }

        test("listBooksByContributor returns all books linked to the contributor") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                val bookRepo = deps.bookRepo
                runTest {
                    val contributorId = contributorRepo.resolveOrCreate("Brandon Sanderson", sortName = null)
                    bookRepo.upsert(bookFixtureWithContributor("b1", "The Way of Kings", contributorId))
                    bookRepo.upsert(bookFixtureWithContributor("b2", "Words of Radiance", contributorId, rootRelPath = "WoR"))

                    val result = service.listBooksByContributor(contributorId)

                    val success = result.shouldBeInstanceOf<AppResult.Success<List<BookSyncPayload>>>()
                    success.data shouldHaveSize 2
                }
            }
        }

        test("listBooksByContributor returns empty list when contributor has no books") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                runTest {
                    val contributorId = contributorRepo.resolveOrCreate("Unknown Author", sortName = null)

                    val result = service.listBooksByContributor(contributorId)

                    val success = result.shouldBeInstanceOf<AppResult.Success<List<BookSyncPayload>>>()
                    success.data.shouldBeEmpty()
                }
            }
        }

        // ── updateContributor ──────────────────────────────────────────────────

        test("updateContributor applies the name patch when the contributor exists") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                runTest {
                    val id = contributorRepo.resolveOrCreate("Brandon Sanderson", sortName = null)

                    val result = service.updateContributor(id, ContributorUpdate(name = "B. Sanderson"))

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                    val reread = contributorRepo.findById(id.value)
                    reread.shouldNotBeNull()
                    reread.name shouldBe "B. Sanderson"
                }
            }
        }

        test("updateContributor returns ContributorError.NotFound when the contributor does not exist") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val service = makeServiceAndDeps(db).service
                runTest {
                    val result =
                        service.updateContributor(ContributorId("ghost"), ContributorUpdate(name = "Anyone"))

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<ContributorError.NotFound>()
                }
            }
        }

        test("updateContributor on a deleted contributor is NotFound and leaves it deleted") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                runTest {
                    val id = contributorRepo.resolveOrCreate("Brandon Sanderson", sortName = null)
                    service.deleteContributor(id).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val result = service.updateContributor(id, ContributorUpdate(name = "Renamed"))

                    result
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<ContributorError.NotFound>()
                    val reread = contributorRepo.findById(id.value).shouldNotBeNull()
                    reread.deletedAt.shouldNotBeNull()
                    reread.name shouldBe "Brandon Sanderson"
                }
            }
        }

        test("updateContributor on a merged-away contributor is NotFound and leaves it merged") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                runTest {
                    val sourceId = contributorRepo.resolveOrCreate("B. Sanderson", sortName = null)
                    val targetId = contributorRepo.resolveOrCreate("Brandon Sanderson", sortName = null)
                    service.mergeContributors(sourceId, targetId).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val result = service.updateContributor(sourceId, ContributorUpdate(name = "Renamed"))

                    result
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<ContributorError.NotFound>()
                    val reread = contributorRepo.findById(sourceId.value).shouldNotBeNull()
                    reread.deletedAt.shouldNotBeNull()
                    reread.name shouldBe "B. Sanderson"
                }
            }
        }

        // ── deleteContributor ──────────────────────────────────────────────────

        test("deleteContributor cascades — drops junctions, re-upserts affected books, soft-deletes the contributor") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                val bookRepo = deps.bookRepo
                runTest {
                    val targetId = contributorRepo.resolveOrCreate("Brandon Sanderson", sortName = null)
                    val coauthorId = contributorRepo.resolveOrCreate("Janci Patterson", sortName = null)
                    // b1 has both contributors; b2 has only the target.
                    bookRepo.upsert(bookFixtureWithTwoContributors("b1", "The Way of Kings", targetId, coauthorId))
                    bookRepo.upsert(bookFixtureWithContributor("b2", "Words of Radiance", targetId, rootRelPath = "WoR"))
                    val initialB1 =
                        bookRepo.findById(
                            com.calypsan.listenup.core
                                .BookId("b1"),
                        )!!
                    val initialB2 =
                        bookRepo.findById(
                            com.calypsan.listenup.core
                                .BookId("b2"),
                        )!!

                    val result = service.deleteContributor(targetId)

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    // Junction rows for the deleted contributor are gone.
                    val remainingBookIds = readBookIdsForContributor(db, targetId.value)
                    remainingBookIds.shouldBeEmpty()

                    // b1 was re-upserted: revision bumped, target contributor stripped, coauthor preserved.
                    val updatedB1 =
                        bookRepo.findById(
                            com.calypsan.listenup.core
                                .BookId("b1"),
                        )!!
                    updatedB1.revision shouldNotBe initialB1.revision
                    updatedB1.contributors.map { it.id } shouldBe listOf(coauthorId.value)

                    // b2 was re-upserted: revision bumped, contributor list empty.
                    val updatedB2 =
                        bookRepo.findById(
                            com.calypsan.listenup.core
                                .BookId("b2"),
                        )!!
                    updatedB2.revision shouldNotBe initialB2.revision
                    updatedB2.contributors.shouldBeEmpty()

                    // Contributor is soft-deleted (deletedAt is set; getContributor still surfaces it
                    // for read-after-delete since findById bypasses the tombstone filter).
                    val tombstone = contributorRepo.findById(targetId.value)
                    tombstone.shouldNotBeNull()
                    tombstone.deletedAt shouldNotBe null
                }
            }
        }

        test("deleteContributor succeeds when the contributor has no linked books") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                runTest {
                    val targetId = contributorRepo.resolveOrCreate("Lone Wolf", sortName = null)

                    val result = service.deleteContributor(targetId)

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                    val tombstone = contributorRepo.findById(targetId.value)
                    tombstone.shouldNotBeNull()
                    tombstone.deletedAt shouldNotBe null
                }
            }
        }

        test("deleteContributor returns ContributorError.NotFound when the contributor does not exist") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val service = makeServiceAndDeps(db).service
                runTest {
                    val result = service.deleteContributor(ContributorId("ghost"))

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<ContributorError.NotFound>()
                }
            }
        }

        test("updateContributor frames the current photo and marks the photo hand-edited") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeServiceAndDeps(this)
                runTest {
                    val id = deps.contributorRepo.resolveOrCreate("Ray Porter", sortName = null)
                    deps.contributorRepo.upsert(deps.contributorRepo.findById(id.value)!!.copy(imagePath = "contributors/abc.jpg"))
                    val framing = ImageFraming("abc", ImageFrame(0.5, 0.2, 1.5))

                    deps.service.updateContributor(id, ContributorUpdate(imageFrame = framing)).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val stored = deps.contributorRepo.findById(id.value)!!
                    stored.imageFrame shouldBe framing
                    stored.fieldProvenance.getValue(ContributorField.PHOTO).kind shouldBe FieldSourceKind.USER
                }
            }
        }

        test("updateContributor refuses an out-of-range frame with a ValidationError naming imageFrame") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeServiceAndDeps(this)
                runTest {
                    val id = deps.contributorRepo.resolveOrCreate("Ray Porter", sortName = null)
                    deps.contributorRepo.upsert(deps.contributorRepo.findById(id.value)!!.copy(imagePath = "contributors/abc.jpg"))

                    val result = deps.service.updateContributor(id, ContributorUpdate(imageFrame = ImageFraming("abc", ImageFrame(0.5, 0.5, 5.0))))

                    result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<ValidationError>().field shouldBe "imageFrame"
                    deps.contributorRepo.findById(id.value)!!.imageFrame.shouldBeNull()
                }
            }
        }

        test("updateContributor drops a frame made for another photo and keeps the photo's own frame") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeServiceAndDeps(this)
                runTest {
                    val id = deps.contributorRepo.resolveOrCreate("Ray Porter", sortName = null)
                    val own = ImageFraming("abc", ImageFrame(0.5, 0.2, 1.5))
                    deps.contributorRepo.upsert(deps.contributorRepo.findById(id.value)!!.copy(imagePath = "contributors/abc.jpg", imageFrame = own))

                    deps.service
                        .updateContributor(id, ContributorUpdate(imageFrame = ImageFraming("stale", ImageFrame(0.1, 0.1, 2.0))))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    deps.contributorRepo.findById(id.value)!!.imageFrame shouldBe own
                }
            }
        }

        test("a new photo patched without a frame clears the old photo's frame") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeServiceAndDeps(this)
                runTest {
                    val id = deps.contributorRepo.resolveOrCreate("Ray Porter", sortName = null)
                    deps.contributorRepo.upsert(
                        deps.contributorRepo.findById(id.value)!!.copy(imagePath = "contributors/abc.jpg", imageFrame = ImageFraming("abc", ImageFrame.Centered)),
                    )

                    deps.service.updateContributor(id, ContributorUpdate(imagePath = "contributors/def.jpg")).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    deps.contributorRepo.findById(id.value)!!.imageFrame.shouldBeNull()
                }
            }
        }
    })

private data class ServiceDeps(
    val service: ContributorServiceImpl,
    val contributorRepo: ContributorRepository,
    val bookRepo: BookRepository,
)

private fun makeServiceAndDeps(db: SqlTestDatabases): ServiceDeps {
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    val contributorRepo = ContributorRepository(db = db.sql, bus = bus, registry = syncRegistry)
    val seriesRepo = SeriesRepository(db.sql, bus, syncRegistry)
    val bookRepo =
        BookRepository(
            db = db.sql,
            driver = db.driver,
            bus = bus,
            registry = syncRegistry,
            contributorRepository = contributorRepo,
            seriesRepository = seriesRepo,
            genreRepository = GenreRepository(db.sql, bus, syncRegistry),
        )
    val service =
        ContributorServiceImpl(
            contributorRepo = contributorRepo,
            bookRepo = bookRepo,
            sqlDb = db.sql,
            accessPolicy = BookAccessPolicy(db.sql, db.driver),
            principal = rootPrincipal(),
        )
    return ServiceDeps(service, contributorRepo, bookRepo)
}

/** Distinct book IDs currently linked to [contributorId] via any junction row. */
private suspend fun readBookIdsForContributor(
    db: SqlTestDatabases,
    contributorId: String,
): List<String> =
    withContext(Dispatchers.IO) {
        db.sql.bookContributorsQueries
            .bookIdsForContributor(contributor_id = contributorId)
            .executeAsList()
    }

private fun bookFixtureWithContributor(
    id: String,
    title: String,
    contributorId: ContributorId,
    rootRelPath: String = "Sanderson/$title",
): BookSyncPayload =
    BookSyncPayload(
        id = id,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = title,
        sortTitle = title,
        subtitle = null,
        description = null,
        publishYear = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = null,
        abridged = false,
        explicit = false,
        hasScanWarning = false,
        totalDuration = 3_600_000L,
        cover = null,
        rootRelPath = rootRelPath,
        inode = null,
        scannedAt = 1_730_000_000_000L,
        contributors =
            listOf(
                BookContributorPayload(
                    id = contributorId.value,
                    name = "Brandon Sanderson",
                    sortName = null,
                    role = "author",
                    creditedAs = null,
                ),
            ),
        series = emptyList(),
        audioFiles =
            listOf(
                BookAudioFilePayload(
                    id = "af1",
                    index = 0,
                    filename = "01.m4b",
                    format = "m4b",
                    codec = "aac",
                    duration = 3_600_000L,
                    size = 500_000_000L,
                ),
            ),
        chapters =
            listOf(
                BookChapterPayload(id = "ch1", title = "Prologue", duration = 1_000_000L, startTime = 0L),
            ),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )

private fun bookFixtureWithTwoContributors(
    id: String,
    title: String,
    firstId: ContributorId,
    secondId: ContributorId,
): BookSyncPayload =
    bookFixtureWithContributor(id, title, firstId).copy(
        contributors =
            listOf(
                BookContributorPayload(
                    id = firstId.value,
                    name = "Brandon Sanderson",
                    sortName = null,
                    role = "author",
                    creditedAs = null,
                ),
                BookContributorPayload(
                    id = secondId.value,
                    name = "Janci Patterson",
                    sortName = null,
                    role = "author",
                    creditedAs = null,
                ),
            ),
    )
