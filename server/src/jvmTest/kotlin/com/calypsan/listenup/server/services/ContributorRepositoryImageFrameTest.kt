package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.api.dto.image.ImageFraming
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * The one rule that makes "a new photo clears the frame" true on every path: [ContributorRepository] keeps a frame
 * only while it was made for the photo being written (spec §3.2).
 */
class ContributorRepositoryImageFrameTest :
    FunSpec({
        val framing = ImageFraming("abc", ImageFrame(0.5, 0.2, 1.5))

        test("a frame made for the photo is stored and read back") {
            withSqlDatabase {
                val repo = ContributorRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val id = repo.resolveOrCreate("Ray Porter", sortName = null)
                    repo.upsert(repo.findById(id.value)!!.copy(imagePath = "contributors/abc.jpg", imageFrame = framing))

                    repo.findById(id.value).shouldNotBeNull().imageFrame shouldBe framing
                }
            }
        }

        test("a frame made for another photo is not stored") {
            withSqlDatabase {
                val repo = ContributorRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val id = repo.resolveOrCreate("Ray Porter", sortName = null)
                    repo.upsert(repo.findById(id.value)!!.copy(imagePath = "contributors/def.jpg", imageFrame = framing))

                    repo
                        .findById(id.value)
                        .shouldNotBeNull()
                        .imageFrame
                        .shouldBeNull()
                }
            }
        }

        test("a new photo through any write clears the old photo's frame; a write that keeps the photo keeps it") {
            withSqlDatabase {
                val repo = ContributorRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val id = repo.resolveOrCreate("Ray Porter", sortName = null)
                    repo.upsert(repo.findById(id.value)!!.copy(imagePath = "contributors/abc.jpg", imageFrame = framing))

                    repo.upsert(repo.findById(id.value)!!.copy(description = "A narrator."))
                    repo.findById(id.value)!!.imageFrame shouldBe framing

                    repo.upsert(repo.findById(id.value)!!.copy(imagePath = "contributors/def.jpg"))
                    repo.findById(id.value)!!.imageFrame.shouldBeNull()
                }
            }
        }

        test("a frame column this build cannot read reads as no frame") {
            withSqlDatabase {
                val repo = ContributorRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val id = repo.resolveOrCreate("Ray Porter", sortName = null)
                    sql.contributorsQueries.updateImageFrame(image_frame = "not json", id = id.value)

                    repo
                        .findById(id.value)
                        .shouldNotBeNull()
                        .imageFrame
                        .shouldBeNull()
                }
            }
        }
    })
