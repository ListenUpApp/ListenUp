package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BookChapterSourceColumnTest :
    FunSpec({
        test("books.chapter_source exists and defaults to 'embedded'") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook(bookId = "b-cs")
                sql.booksQueries
                    .selectById("b-cs")
                    .executeAsOne()
                    .chapter_source shouldBe "embedded"
            }
        }
    })
