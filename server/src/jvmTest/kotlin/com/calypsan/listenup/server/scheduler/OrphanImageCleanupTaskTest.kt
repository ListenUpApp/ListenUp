@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.scheduler

import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.api.sync.SeriesSyncPayload
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.io.hashBytesSha256
import com.calypsan.listenup.server.io.writeBytes
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.time.Clock
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Pins the liveness rule of [OrphanImageCleanupTask] against the filenames production actually
 * writes. Every writer — `ContributorMetadataApplier` and the `PUT` upload path — names a photo or
 * cover by the **SHA-256 of its bytes**, never by the entity id; a rule that matched stems against
 * ids called every real image an orphan and deleted it. These fixtures write real bytes and name the
 * file by their real hash, exactly as production does.
 *
 * Fixture files are written moments before the sweep, so every case that expects a deletion runs
 * the task on a clock past [OrphanImageCleanupTask.ORPHAN_GRACE] — the one case that does not is
 * the grace window's own test.
 *
 * `java.nio.file.Files.createTempDirectory` is grandfathered — kotlinx-io has no equivalent
 * "create a temp directory" API.
 */
class OrphanImageCleanupTaskTest :
    FunSpec({

        /** A clock far enough ahead that a file written now reads as older than the grace window. */
        val pastGrace = FixedClock(Clock.System.now() + OrphanImageCleanupTask.ORPHAN_GRACE * 2)

        fun makeContributorRepo(sql: ListenUpDatabase): ContributorRepository = ContributorRepository(sql, ChangeBus(), SyncRegistry())

        fun makeSeriesRepo(sql: ListenUpDatabase): SeriesRepository = SeriesRepository(sql, ChangeBus(), SyncRegistry())

        fun task(
            contributorRepo: ContributorRepository,
            seriesRepo: SeriesRepository,
            home: String,
            clock: Clock = pastGrace,
        ) = OrphanImageCleanupTask(contributorRepo, seriesRepo, Path(home), clock = clock)

        /** Writes [bytes] under [dir] named by their content hash, as every production writer does. */
        fun writeContentAddressed(
            dir: Path,
            bytes: ByteArray,
            extension: String = "jpg",
        ): Path {
            SystemFileSystem.createDirectories(dir)
            return Path(dir, "${hashBytesSha256(bytes)}.$extension").also { it.writeBytes(bytes) }
        }

        /** A contributor row whose `imagePath` points at [imagePath] (relative to the image home). */
        suspend fun ContributorRepository.seed(
            id: String,
            imagePath: String?,
        ) {
            upsert(
                ContributorSyncPayload(
                    id = id,
                    name = "Author $id",
                    sortName = "Author $id",
                    revision = 0L,
                    updatedAt = 0L,
                    createdAt = 0L,
                    deletedAt = null,
                    imagePath = imagePath,
                ),
            )
        }

        /** A series row whose `coverPath` points at [coverPath] (relative to the image home). */
        suspend fun SeriesRepository.seed(
            id: String,
            coverPath: String?,
        ) {
            upsert(
                SeriesSyncPayload(
                    id = id,
                    name = "Series $id",
                    sortName = "Series $id",
                    revision = 0L,
                    updatedAt = 0L,
                    createdAt = 0L,
                    deletedAt = null,
                    coverPath = coverPath,
                ),
            )
        }

        test("a contributor photo named by its content hash survives when the row references it") {
            withSqlDatabase {
                val home = Files.createTempDirectory("orphan-contributor-live-").toString()
                val contributorRepo = makeContributorRepo(sql)
                val seriesRepo = makeSeriesRepo(sql)
                runTest {
                    val photo = writeContentAddressed(Path(home, "contributors"), "brandon".encodeToByteArray())
                    contributorRepo.seed("c-live", imagePath = "contributors/${photo.name}")

                    task(contributorRepo, seriesRepo, home).runOnce()

                    SystemFileSystem.exists(photo) shouldBe true
                }
            }
        }

        test("a series cover named by its content hash survives when the row references it") {
            withSqlDatabase {
                val home = Files.createTempDirectory("orphan-series-live-").toString()
                val contributorRepo = makeContributorRepo(sql)
                val seriesRepo = makeSeriesRepo(sql)
                runTest {
                    val cover = writeContentAddressed(Path(home, "series"), "stormlight".encodeToByteArray())
                    seriesRepo.seed("s-live", coverPath = "series/${cover.name}")

                    task(contributorRepo, seriesRepo, home).runOnce()

                    SystemFileSystem.exists(cover) shouldBe true
                }
            }
        }

        test("a file no live row references is deleted") {
            withSqlDatabase {
                val home = Files.createTempDirectory("orphan-unreferenced-").toString()
                val contributorRepo = makeContributorRepo(sql)
                val seriesRepo = makeSeriesRepo(sql)
                runTest {
                    val orphanPhoto = writeContentAddressed(Path(home, "contributors"), "nobody".encodeToByteArray())
                    val orphanCover = writeContentAddressed(Path(home, "series"), "no-series".encodeToByteArray())
                    contributorRepo.seed("c-other", imagePath = "contributors/somethingelse.jpg")

                    task(contributorRepo, seriesRepo, home).runOnce()

                    SystemFileSystem.exists(orphanPhoto) shouldBe false
                    SystemFileSystem.exists(orphanCover) shouldBe false
                }
            }
        }

        test("a file referenced only by a tombstoned row is deleted") {
            withSqlDatabase {
                val home = Files.createTempDirectory("orphan-tombstoned-").toString()
                val contributorRepo = makeContributorRepo(sql)
                val seriesRepo = makeSeriesRepo(sql)
                runTest {
                    val photo = writeContentAddressed(Path(home, "contributors"), "gone-author".encodeToByteArray())
                    val cover = writeContentAddressed(Path(home, "series"), "gone-series".encodeToByteArray())
                    contributorRepo.seed("c-dead", imagePath = "contributors/${photo.name}")
                    seriesRepo.seed("s-dead", coverPath = "series/${cover.name}")
                    contributorRepo.softDelete(ContributorId("c-dead"))
                    seriesRepo.softDelete(SeriesId("s-dead"))

                    task(contributorRepo, seriesRepo, home).runOnce()

                    SystemFileSystem.exists(photo) shouldBe false
                    SystemFileSystem.exists(cover) shouldBe false
                }
            }
        }

        // The applier writes the file first and commits the row second. A sweep between the two
        // must not turn that ordering into a deleted photo.
        test("a file written moments ago is not deleted even if no row references it yet") {
            withSqlDatabase {
                val home = Files.createTempDirectory("orphan-grace-").toString()
                val contributorRepo = makeContributorRepo(sql)
                val seriesRepo = makeSeriesRepo(sql)
                runTest {
                    val justWritten = writeContentAddressed(Path(home, "contributors"), "in-flight".encodeToByteArray())

                    task(contributorRepo, seriesRepo, home, clock = Clock.System).runOnce()

                    SystemFileSystem.exists(justWritten) shouldBe true
                }
            }
        }

        // Plan 025 may introduce .png/.webp names; liveness is "a live row points at this file",
        // whatever its extension.
        test("the rule is extension-agnostic: a referenced png survives and an unreferenced png is deleted") {
            withSqlDatabase {
                val home = Files.createTempDirectory("orphan-png-").toString()
                val contributorRepo = makeContributorRepo(sql)
                val seriesRepo = makeSeriesRepo(sql)
                runTest {
                    val dir = Path(home, "contributors")
                    val referenced = writeContentAddressed(dir, "png-live".encodeToByteArray(), extension = "png")
                    val unreferenced = writeContentAddressed(dir, "png-orphan".encodeToByteArray(), extension = "png")
                    contributorRepo.seed("c-png", imagePath = "contributors/${referenced.name}")

                    task(contributorRepo, seriesRepo, home).runOnce()

                    SystemFileSystem.exists(referenced) shouldBe true
                    SystemFileSystem.exists(unreferenced) shouldBe false
                }
            }
        }

        test("runOnce is a no-op when the image directories do not exist") {
            withSqlDatabase {
                val home = Files.createTempDirectory("orphan-nodir-").toString()
                val sweep = task(makeContributorRepo(sql), makeSeriesRepo(sql), home)
                runTest {
                    // must not throw
                    sweep.runOnce()
                }
            }
        }

        test("runOnce on empty directories returns without error") {
            withSqlDatabase {
                val home = Files.createTempDirectory("orphan-emptydir-").toString()
                val sweep = task(makeContributorRepo(sql), makeSeriesRepo(sql), home)
                runTest {
                    SystemFileSystem.createDirectories(Path(home, "contributors"))
                    SystemFileSystem.createDirectories(Path(home, "series"))
                    // must not throw
                    sweep.runOnce()
                }
            }
        }
    })
