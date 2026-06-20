@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import com.calypsan.listenup.server.testing.asSqlDatabase

class BookRepositoryIdAsStringTest :
    FunSpec({
        test("idAsString returns the raw value, not the value-class toString") {
            withInMemoryDatabase {
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val repo =
                    BookRepository(
                        db = this.asSqlDatabase(),
                        exposedDb = this,
                        bus = bus,
                        registry = registry,
                        contributorRepository = ContributorRepository(this.asSqlDatabase(), bus, registry),
                        seriesRepository = SeriesRepository(this.asSqlDatabase(), bus, registry),
                        genreRepository = GenreRepository(this.asSqlDatabase(), bus, registry),
                    )
                repo.idAsStringForTest(BookId("abc-123")) shouldBe "abc-123"
            }
        }

        test("BookRepository.domainName is 'books'") {
            withInMemoryDatabase {
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val repo =
                    BookRepository(
                        db = this.asSqlDatabase(),
                        exposedDb = this,
                        bus = bus,
                        registry = registry,
                        contributorRepository = ContributorRepository(this.asSqlDatabase(), bus, registry),
                        seriesRepository = SeriesRepository(this.asSqlDatabase(), bus, registry),
                        genreRepository = GenreRepository(this.asSqlDatabase(), bus, registry),
                    )
                repo.domainName shouldBe "books"
            }
        }
    })
