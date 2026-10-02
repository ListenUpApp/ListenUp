package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.MetadataApplySelection
import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.hardcover.OneBookAudible
import com.calypsan.listenup.server.hardcover.PHM_ASIN
import com.calypsan.listenup.server.hardcover.audibleHailMary
import com.calypsan.listenup.server.hardcover.lookupRig
import com.calypsan.listenup.server.hardcover.scannedBook
import com.calypsan.listenup.server.metadata.spi.BookCoreMeta
import com.calypsan.listenup.server.metadata.spi.BookCoreSource
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.metadata.spi.GenreKind
import com.calypsan.listenup.server.metadata.spi.GenreMeta
import com.calypsan.listenup.server.metadata.spi.GenreSource
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.MoodSource
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList

private val US = MetadataLocale("us")

/** A Hardcover stand-in that records which book each compose named. */
private class RecordingHardcover :
    BookCoreSource,
    GenreSource,
    MoodSource {
    override val id = MetadataProviderId.HARDCOVER
    val bookIds = CopyOnWriteArrayList<String?>()

    override suspend fun getBookCore(
        book: BookIdentity,
        locale: MetadataLocale,
        refresh: Boolean,
    ): AppResult<BookCoreMeta?> {
        bookIds += book.bookId
        return AppResult.Success(BookCoreMeta(description = "A lone astronaut must save the earth."))
    }

    override suspend fun getGenres(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<List<GenreMeta>?> = AppResult.Success(listOf(GenreMeta("Space Opera", GenreKind.GENRE)))

    override suspend fun getMoods(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<List<String>?> = AppResult.Success(listOf("Hopeful", "Funny"))
}

private val TAKE_MOODS =
    MetadataApplySelection(
        title = true,
        subtitle = false,
        description = true,
        publisher = false,
        releaseDate = false,
        language = false,
        cover = false,
        authorAsins = emptySet(),
        narratorAsins = emptySet(),
        seriesAsins = emptySet(),
        moods = setOf("Hopeful"),
    )

/** #1542 through the lookup service: the preview names the book, Hardcover fills, apply writes what was chosen. */
class MetadataLookupHardcoverTest :
    FunSpec({
        test("the preview hands Hardcover the book being matched, and shows its moods and genres with provenance") {
            withSqlDatabase {
                runTest {
                    sql.seedTestLibraryAndFolder()
                    val hardcover = RecordingHardcover()
                    val rig = lookupRig(this@withSqlDatabase, OneBookAudible(audibleHailMary()), listOf(hardcover))
                    rig.books.upsert(scannedBook("book-1"), clientOpId = null)

                    val preview =
                        rig.service
                            .getBookMetadata(PHM_ASIN, US, BookId("book-1"))
                            .shouldBeInstanceOf<AppResult.Success<MetadataBook?>>()
                            .data!!

                    hardcover.bookIds shouldContain "book-1"
                    preview.title shouldBe "Project Hail Mary"
                    preview.description shouldBe "A lone astronaut must save the earth."
                    preview.moods shouldBe listOf("Hopeful", "Funny")
                    preview.genres shouldBe listOf("Science Fiction", "Space Opera")
                    val provenance = preview.matchProvenance!!
                    provenance.fallbackFields[BookField.MOODS] shouldBe "Hardcover"
                    provenance.fallbackFields[BookField.DESCRIPTION] shouldBe "Hardcover"
                    provenance.genreSources shouldBe mapOf("Space Opera" to "Hardcover")
                }
            }
        }

        test("an older client that names no book still gets Hardcover's fields, resolved without one") {
            withSqlDatabase {
                runTest {
                    sql.seedTestLibraryAndFolder()
                    val hardcover = RecordingHardcover()
                    val rig = lookupRig(this@withSqlDatabase, OneBookAudible(audibleHailMary()), listOf(hardcover))

                    val preview = (rig.service.getBookMetadata(PHM_ASIN, US) as AppResult.Success).data!!

                    hardcover.bookIds.toSet() shouldBe setOf(null)
                    preview.moods shouldBe listOf("Hopeful", "Funny")
                }
            }
        }

        test("a book the caller can't see is matched as if no book was named") {
            withSqlDatabase {
                runTest {
                    sql.seedTestLibraryAndFolder()
                    sql.seedTestUser("m1")
                    val hardcover = RecordingHardcover()
                    val member = UserPrincipal(UserId("m1"), SessionId("s"), UserRole.MEMBER)
                    val rig = lookupRig(this@withSqlDatabase, OneBookAudible(audibleHailMary()), listOf(hardcover), caller = member)
                    rig.books.upsert(scannedBook("book-1"), clientOpId = null)

                    rig.service.getBookMetadata(PHM_ASIN, US, BookId("book-1"))

                    hardcover.bookIds.toSet() shouldBe setOf(null)
                }
            }
        }

        test("apply composes with the same book, writes the ticked mood, and leaves the unticked one off") {
            withSqlDatabase {
                runTest {
                    sql.seedTestLibraryAndFolder()
                    val hardcover = RecordingHardcover()
                    val rig = lookupRig(this@withSqlDatabase, OneBookAudible(audibleHailMary()), listOf(hardcover))
                    rig.books.upsert(scannedBook("book-1"), clientOpId = null)

                    rig.service.applyBookMetadata(BookId("book-1"), PHM_ASIN, US, TAKE_MOODS).shouldBeInstanceOf<AppResult.Success<*>>()

                    hardcover.bookIds.toSet() shouldBe setOf("book-1")
                    rig.moodNames("book-1") shouldContainExactlyInAnyOrder listOf("Hopeful")
                    rig.books.findById(BookId("book-1"))!!.description shouldBe "A lone astronaut must save the earth."
                }
            }
        }
    })
