@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BookRepositoryIdAsStringTest :
    FunSpec({
        test("idAsString returns the raw value, not the value-class toString") {
            withInMemoryDatabase {
                val repo = BookRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                repo.idAsStringForTest(BookId("abc-123")) shouldBe "abc-123"
            }
        }

        test("BookRepository.domainName is 'books'") {
            withInMemoryDatabase {
                val repo = BookRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                repo.domainName shouldBe "books"
            }
        }
    })
