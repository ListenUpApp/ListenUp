@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.bookPayloadFixture
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class BookAudioFileRichFormatTest :
    FunSpec({

        test("rich audio format fields survive a persist-then-read round-trip") {
            withSqlDatabase {
                val repo = newRepo()
                runTest {
                    sql.seedTestLibraryAndFolder()
                    val audioFile =
                        BookAudioFilePayload(
                            id = "",
                            index = 0,
                            filename = "book.m4b",
                            format = "m4b",
                            codec = "aac",
                            duration = 3_600_000L,
                            size = 50_000_000L,
                            codecProfile = "xhe",
                            spatial = "stereo",
                            bitrate = 128_000,
                            sampleRate = 44_100,
                            channels = 2,
                        )
                    val payload =
                        bookPayloadFixture(
                            id = "b-rich",
                            title = "Rich Format Book",
                            audioFiles = listOf(audioFile),
                        )
                    repo.upsert(payload)
                    val read = repo.readPayloadForTest("b-rich")
                    val readFile = read?.audioFiles?.single()
                    readFile?.codecProfile shouldBe "xhe"
                    readFile?.spatial shouldBe "stereo"
                    readFile?.bitrate shouldBe 128_000
                    readFile?.sampleRate shouldBe 44_100
                    readFile?.channels shouldBe 2
                }
            }
        }
    })

private fun SqlTestDatabases.newRepo(): BookRepository {
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
