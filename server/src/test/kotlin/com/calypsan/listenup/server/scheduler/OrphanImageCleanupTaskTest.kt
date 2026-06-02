package com.calypsan.listenup.server.scheduler

import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Unit tests for [OrphanImageCleanupTask].
 *
 * `java.nio.file.Files.createTempDirectory` is grandfathered — kotlinx-io has
 * no equivalent "create a temp directory" API.
 */
class OrphanImageCleanupTaskTest :
    FunSpec({

        fun makeContributorRepo(
            db: org.jetbrains.exposed.v1.jdbc.Database,
        ): ContributorRepository = ContributorRepository(db, ChangeBus(), SyncRegistry())

        fun makeSeriesRepo(
            db: org.jetbrains.exposed.v1.jdbc.Database,
        ): SeriesRepository = SeriesRepository(db, ChangeBus(), SyncRegistry())

        /** Creates a tiny empty file at [path] (no bytes needed — just existence). */
        fun touch(path: Path) {
            val parent = path.parent
            if (parent != null && !SystemFileSystem.exists(parent)) {
                SystemFileSystem.createDirectories(parent)
            }
            SystemFileSystem.sink(path).close()
        }

        test("runOnce deletes orphan and tombstoned files; live contributor image survives") {
            withInMemoryDatabase {
                val tempDir = Files.createTempDirectory("orphan-contributor-test-").toString()
                val db = this
                val contributorRepo = makeContributorRepo(db)
                val seriesRepo = makeSeriesRepo(db)

                runTest {
                    // Seed a live contributor and a tombstoned contributor via the substrate
                    val liveId = contributorRepo.resolveOrCreate("Brandon Sanderson").value
                    val tombstonedId = contributorRepo.resolveOrCreate("Deleted Author").value
                    contributorRepo.softDelete(
                        com.calypsan.listenup.core
                            .ContributorId(tombstonedId),
                        userId = null,
                    )

                    val contributorDir = Path(tempDir, "contributors")
                    // live file — must survive
                    touch(Path(contributorDir, "$liveId.jpg"))
                    // tombstoned — must be deleted
                    touch(Path(contributorDir, "$tombstonedId.jpg"))
                    // orphan (unknown id) — must be deleted
                    touch(Path(contributorDir, "completely-unknown-id.jpg"))
                    // non-jpg — must be ignored (not deleted)
                    touch(Path(contributorDir, "$liveId.png"))

                    val task =
                        OrphanImageCleanupTask(
                            contributorRepository = contributorRepo,
                            seriesRepository = seriesRepo,
                            imageHome = Path(tempDir),
                        )
                    task.runOnce()

                    SystemFileSystem.exists(Path(contributorDir, "$liveId.jpg")) shouldBe true
                    SystemFileSystem.exists(Path(contributorDir, "$tombstonedId.jpg")) shouldBe false
                    SystemFileSystem.exists(Path(contributorDir, "completely-unknown-id.jpg")) shouldBe false
                    SystemFileSystem.exists(Path(contributorDir, "$liveId.png")) shouldBe true
                }
            }
        }

        test("runOnce deletes orphan series cover; live series cover survives") {
            withInMemoryDatabase {
                val tempDir = Files.createTempDirectory("orphan-series-test-").toString()
                val db = this
                val contributorRepo = makeContributorRepo(db)
                val seriesRepo = makeSeriesRepo(db)

                runTest {
                    val liveSeriesId = seriesRepo.resolveOrCreate("The Stormlight Archive").value
                    val seriesDir = Path(tempDir, "series")
                    // live file — must survive
                    touch(Path(seriesDir, "$liveSeriesId.jpg"))
                    // orphan — must be deleted
                    touch(Path(seriesDir, "orphan-series-id.jpg"))

                    val task =
                        OrphanImageCleanupTask(
                            contributorRepository = contributorRepo,
                            seriesRepository = seriesRepo,
                            imageHome = Path(tempDir),
                        )
                    task.runOnce()

                    SystemFileSystem.exists(Path(seriesDir, "$liveSeriesId.jpg")) shouldBe true
                    SystemFileSystem.exists(Path(seriesDir, "orphan-series-id.jpg")) shouldBe false
                }
            }
        }

        test("runOnce is a no-op when the image directories do not exist") {
            withInMemoryDatabase {
                val tempDir = Files.createTempDirectory("orphan-nodir-test-").toString()
                val db = this
                val task =
                    OrphanImageCleanupTask(
                        contributorRepository = makeContributorRepo(db),
                        seriesRepository = makeSeriesRepo(db),
                        imageHome = Path(tempDir),
                    )
                runTest {
                    // must not throw
                    task.runOnce()
                }
            }
        }

        test("runOnce on empty directories returns without error") {
            withInMemoryDatabase {
                val tempDir = Files.createTempDirectory("orphan-emptydir-test-").toString()
                val db = this
                val task =
                    OrphanImageCleanupTask(
                        contributorRepository = makeContributorRepo(db),
                        seriesRepository = makeSeriesRepo(db),
                        imageHome = Path(tempDir),
                    )
                runTest {
                    SystemFileSystem.createDirectories(Path(tempDir, "contributors"))
                    SystemFileSystem.createDirectories(Path(tempDir, "series"))
                    // must not throw
                    task.runOnce()
                }
            }
        }
    })
