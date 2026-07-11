@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class BookRepositoryReadTest :
    FunSpec({

        test("readPayload returns null for absent book") {
            withSqlDatabase {
                val repo = makeRepo()
                runTest {
                    repo.readPayloadForTest("missing").shouldBeNull()
                }
            }
        }

        test("readPayload assembles the full aggregate: book + contributors + series + chapters + audio files") {
            withSqlDatabase {
                val repo = makeRepo()
                runTest {
                    sql.seedTestLibraryAndFolder()
                    sql.transaction {
                        sql.booksQueries.insert(
                            id = "b1",
                            library_id = "test-library",
                            folder_id = "test-folder",
                            title = "Way of Kings",
                            sort_title = "Way of Kings",
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
                            total_duration = 162_000_000L,
                            cover_source = "filesystem",
                            cover_path = null,
                            cover_hash = "deadbeef",
                            user_edited_fields = "",
                            root_rel_path = "Sanderson/Way of Kings",
                            inode = null,
                            scanned_at = 1_730_000_000_000L,
                            revision = 1L,
                            created_at = 1_730_000_000_000L,
                            updated_at = 1_730_000_000_000L,
                            deleted_at = null,
                            client_op_id = null,
                        )
                        sql.contributorsQueries.insert(
                            id = "c1",
                            normalized_name = "brandon sanderson",
                            name = "Brandon Sanderson",
                            sort_name = "Sanderson, Brandon",
                            revision = 0L,
                            created_at = 0L,
                            updated_at = 0L,
                            deleted_at = null,
                            client_op_id = null,
                            asin = null,
                            description = null,
                            image_path = null,
                            image_blur_hash = null,
                            birth_date = null,
                            death_date = null,
                            website = null,
                        )
                        sql.contributorsQueries.insert(
                            id = "c2",
                            normalized_name = "michael kramer",
                            name = "Michael Kramer",
                            sort_name = null,
                            revision = 0L,
                            created_at = 0L,
                            updated_at = 0L,
                            deleted_at = null,
                            client_op_id = null,
                            asin = null,
                            description = null,
                            image_path = null,
                            image_blur_hash = null,
                            birth_date = null,
                            death_date = null,
                            website = null,
                        )
                        sql.bookContributorsQueries.insert(
                            book_id = "b1",
                            contributor_id = "c1",
                            role = "author",
                            credited_as = null,
                            ordinal = 0L,
                        )
                        sql.bookContributorsQueries.insert(
                            book_id = "b1",
                            contributor_id = "c2",
                            role = "narrator",
                            credited_as = null,
                            ordinal = 1L,
                        )
                        sql.seriesQueries.insert(
                            id = "s1",
                            normalized_name = "stormlight archive",
                            name = "Stormlight Archive",
                            sort_name = null,
                            revision = 0L,
                            created_at = 0L,
                            updated_at = 0L,
                            deleted_at = null,
                            client_op_id = null,
                            asin = null,
                            description = null,
                            cover_path = null,
                            cover_blur_hash = null,
                        )
                        sql.bookSeriesMembershipsQueries.insert(
                            book_id = "b1",
                            series_id = "s1",
                            sequence = "1",
                            ordinal = 0L,
                        )
                        sql.bookChaptersQueries.insert(
                            book_id = "b1",
                            ordinal = 0L,
                            id = "ch1",
                            title = "Prologue",
                            duration = 1_200_000L,
                            start_time = 0L,
                            part_title = null,
                            book_title = null,
                        )
                        sql.bookChaptersQueries.insert(
                            book_id = "b1",
                            ordinal = 1L,
                            id = "ch2",
                            title = "Chapter 1",
                            duration = 1_800_000L,
                            start_time = 1_200_000L,
                            part_title = null,
                            book_title = null,
                        )
                        sql.bookAudioFilesQueries.insert(
                            book_id = "b1",
                            ordinal = 0L,
                            id = "af1",
                            filename = "01.m4b",
                            format = "m4b",
                            codec = "aac",
                            duration = 162_000_000L,
                            size = 200_000_000L,
                            codecProfile = null,
                            spatial = null,
                            bitrate = null,
                            sampleRate = null,
                            channels = null,
                        )
                    }

                    val payload = repo.readPayloadForTest("b1").shouldNotBeNull()

                    payload.id shouldBe "b1"
                    payload.title shouldBe "Way of Kings"
                    payload.totalDuration shouldBe 162_000_000L
                    payload.rootRelPath shouldBe "Sanderson/Way of Kings"
                    payload.scannedAt shouldBe 1_730_000_000_000L
                    payload.revision shouldBe 1L

                    val cover = payload.cover.shouldNotBeNull()
                    cover.source shouldBe CoverSource.FILESYSTEM
                    cover.hash shouldBe "deadbeef"

                    payload.contributors.size shouldBe 2
                    payload.contributors[0].id shouldBe "c1"
                    payload.contributors[0].role shouldBe "author"
                    payload.contributors[1].id shouldBe "c2"
                    payload.contributors[1].role shouldBe "narrator"

                    payload.series.size shouldBe 1
                    payload.series[0].id shouldBe "s1"
                    payload.series[0].sequence shouldBe "1"

                    payload.chapters.size shouldBe 2
                    payload.chapters.map { it.title } shouldContainExactly listOf("Prologue", "Chapter 1")

                    payload.audioFiles.size shouldBe 1
                    payload.audioFiles[0].filename shouldBe "01.m4b"
                    payload.audioFiles[0].index shouldBe 0
                }
            }
        }

        test("readPayload returns cover = null when coverHash is absent") {
            withSqlDatabase {
                val repo = makeRepo()
                runTest {
                    sql.seedTestLibraryAndFolder()
                    sql.transaction {
                        sql.booksQueries.insert(
                            id = "b2",
                            library_id = "test-library",
                            folder_id = "test-folder",
                            title = "No Cover",
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
                            total_duration = 0L,
                            cover_source = null,
                            cover_path = null,
                            cover_hash = null,
                            user_edited_fields = "",
                            root_rel_path = "no-cover",
                            inode = null,
                            scanned_at = 0L,
                            revision = 1L,
                            created_at = 0L,
                            updated_at = 0L,
                            deleted_at = null,
                            client_op_id = null,
                        )
                    }
                    val payload = repo.readPayloadForTest("b2").shouldNotBeNull()
                    payload.cover.shouldBeNull()
                }
            }
        }
    })

private fun SqlTestDatabases.makeRepo(): BookRepository {
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    return BookRepository(
        db = sql,
        driver = driver,
        bus = bus,
        registry = syncRegistry,
        contributorRepository = ContributorRepository(sql, bus, syncRegistry),
        seriesRepository = SeriesRepository(sql, bus, syncRegistry),
        genreRepository = GenreRepository(sql, bus, syncRegistry),
    )
}
