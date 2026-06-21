@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BookRepositoryIdAsStringTest :
    FunSpec({
        test("idAsString returns the raw value, not the value-class toString") {
            withSqlDatabase {
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
                repo.idAsStringForTest(BookId("abc-123")) shouldBe "abc-123"
            }
        }

        test("BookRepository.domainName is 'books'") {
            withSqlDatabase {
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
                repo.domainName shouldBe "books"
            }
        }
    })
