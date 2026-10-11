package com.calypsan.listenup.server.cover

import com.calypsan.listenup.api.sync.CoverPayload
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.testing.bookPayloadFixture
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path

/** The filesystem-cover fallback judges a book folder's image where a link points, not where it sits. */
class FilesystemCoverContainmentTest :
    FunSpec({

        fun withBookDirs(block: (libraryRoot: Path, bookDir: Path, outside: Path) -> Unit) {
            val libraryRoot = Files.createTempDirectory("cover-lib").toRealPath()
            val outside = Files.createTempDirectory("cover-outside").toRealPath()
            try {
                val bookDir = Files.createDirectories(libraryRoot.resolve("books/b1"))
                block(libraryRoot, bookDir, outside)
            } finally {
                libraryRoot.toFile().deleteRecursively()
                outside.toFile().deleteRecursively()
            }
        }

        fun coverInfoFor(libraryRoot: Path): CoverInfo? {
            var result: CoverInfo? = null
            withSqlDatabase {
                sql.seedTestLibraryAndFolder(folderPath = libraryRoot.toString())
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val repo =
                    BookRepository(
                        db = sql,
                        driver = driver,
                        bus = bus,
                        registry = registry,
                        contributorRepository = ContributorRepository(sql, bus, registry),
                        seriesRepository = SeriesRepository(sql, bus, registry),
                        genreRepository = GenreRepository(sql, bus, registry),
                    )
                runTest {
                    repo.upsert(
                        bookPayloadFixture(
                            id = "b1",
                            title = "Linked",
                            cover = CoverPayload(source = CoverSource.FILESYSTEM, hash = "abc"),
                        ),
                    )
                    result = repo.coverInfo(BookId("b1"))
                }
            }
            return result
        }

        test("the only image in the book folder being a link out of the library resolves no cover") {
            withBookDirs { libraryRoot, bookDir, outside ->
                val target = Files.write(outside.resolve("secret.jpg"), byteArrayOf(1, 2, 3))
                Files.createSymbolicLink(bookDir.resolve("cover.jpg"), target)
                coverInfoFor(libraryRoot).shouldBeNull()
            }
        }

        test("a plain image in the book folder resolves as the filesystem cover") {
            withBookDirs { libraryRoot, bookDir, _ ->
                Files.write(bookDir.resolve("cover.jpg"), byteArrayOf(1, 2, 3))
                val info = coverInfoFor(libraryRoot)
                info.shouldBeInstanceOf<CoverInfo.Filesystem>()
                info.path.name shouldBe "cover.jpg"
            }
        }
    })
