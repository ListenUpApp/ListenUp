package com.calypsan.listenup.server.scanner.sidecar

import com.calypsan.listenup.server.io.hashBytesSha256
import com.calypsan.listenup.server.sidecar.ListenUpSidecar
import com.calypsan.listenup.server.sidecar.SidecarCuratedMetadata
import com.calypsan.listenup.server.sidecar.SidecarIdentity
import com.calypsan.listenup.server.sidecar.SidecarJson
import com.calypsan.listenup.server.sidecar.SidecarWriteStateRepository
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import kotlin.io.path.div
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path

/**
 * Tests for [ListenUpSidecarReader] — the read half of the `listenup.json` round trip.
 * The hash-skip discriminator: a file whose bytes hash to a recorded
 * `sidecar_write_state.content_hash` is [SidecarReadResult.SelfWritten] (our own write —
 * skip re-ingestion); anything else parseable is [SidecarReadResult.External] (a human or
 * another server wrote it — ingest).
 */
class ListenUpSidecarReaderTest :
    FunSpec({

        fun sampleSidecar(title: String): ListenUpSidecar =
            ListenUpSidecar(
                identity = SidecarIdentity(titleAuthor = "$title / Author"),
                metadata = SidecarCuratedMetadata(title = title),
            )

        test("a book dir with a parseable listenup.json and no matching hash reads as External") {
            withSqlDatabase {
                runTest {
                    val bookDir = Files.createTempDirectory("sidecar-reader-")
                    val bytes = SidecarJson.serialize(sampleSidecar("Curated Title"))
                    (bookDir / "listenup.json").writeBytes(bytes)
                    val reader = ListenUpSidecarReader(SidecarWriteStateRepository(sql))

                    val result = reader.read(Path(bookDir.toString()))

                    val external = result.shouldBeInstanceOf<SidecarReadResult.External>()
                    external.sidecar.metadata.title shouldBe "Curated Title"
                }
            }
        }

        test("a file whose hash matches a recorded self-write reads as SelfWritten") {
            withSqlDatabase {
                runTest {
                    sql.seedTestLibraryAndFolder()
                    sql.seedTestBook(bookId = "book1")
                    val bookDir = Files.createTempDirectory("sidecar-reader-")
                    val bytes = SidecarJson.serialize(sampleSidecar("Curated Title"))
                    (bookDir / "listenup.json").writeBytes(bytes)
                    val writeState = SidecarWriteStateRepository(sql)
                    writeState.save("book1", hashBytesSha256(bytes), writtenAtMs = 1L)

                    val reader = ListenUpSidecarReader(writeState)
                    reader.read(Path(bookDir.toString())) shouldBe SidecarReadResult.SelfWritten
                }
            }
        }

        test("a differing hash — the file was edited since we wrote it — reads as External") {
            withSqlDatabase {
                runTest {
                    sql.seedTestLibraryAndFolder()
                    sql.seedTestBook(bookId = "book1")
                    val bookDir = Files.createTempDirectory("sidecar-reader-")
                    val original = SidecarJson.serialize(sampleSidecar("Original"))
                    val writeState = SidecarWriteStateRepository(sql)
                    writeState.save("book1", hashBytesSha256(original), writtenAtMs = 1L)
                    // The on-disk file has been hand-edited since.
                    (bookDir / "listenup.json").writeBytes(SidecarJson.serialize(sampleSidecar("Edited By Hand")))

                    val reader = ListenUpSidecarReader(writeState)
                    val result = reader.read(Path(bookDir.toString()))

                    val external = result.shouldBeInstanceOf<SidecarReadResult.External>()
                    external.sidecar.metadata.title shouldBe "Edited By Hand"
                }
            }
        }

        test("a corrupt listenup.json reads as Absent, never throws") {
            withSqlDatabase {
                runTest {
                    val bookDir = Files.createTempDirectory("sidecar-reader-")
                    (bookDir / "listenup.json").writeText("{{{ not json")
                    val reader = ListenUpSidecarReader(SidecarWriteStateRepository(sql))

                    reader.read(Path(bookDir.toString())) shouldBe SidecarReadResult.Absent
                }
            }
        }

        test("no listenup.json in the book dir reads as Absent") {
            withSqlDatabase {
                runTest {
                    val bookDir = Files.createTempDirectory("sidecar-reader-")
                    val reader = ListenUpSidecarReader(SidecarWriteStateRepository(sql))

                    reader.read(Path(bookDir.toString())) shouldBe SidecarReadResult.Absent
                }
            }
        }
    })
