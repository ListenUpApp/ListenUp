package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.MatchingRepository
import com.calypsan.listenup.client.presentation.match.BookMatchViewModel
import com.calypsan.listenup.client.presentation.match.FakeMatchingRepository
import com.calypsan.listenup.client.presentation.match.MatchReceiptStore
import com.calypsan.listenup.client.presentation.match.MatchReceiptViewModel
import com.calypsan.listenup.client.presentation.match.PersonMatchViewModel
import com.calypsan.listenup.client.presentation.match.FakeContributorRepository
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.test.fake.FakeBookRepository
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Proves Match details can be built from the container. Both ViewModels have `internal` constructors (they take the
 * internal `MatchingRepository` and `MatchReceiptStore`), which `module.verify()` skips — see
 * [BulkEditResolutionTest] — so this resolves them for real, and checks the receipt store is one shared instance:
 * Match details writes the receipt that Book Detail reads.
 */
class BookMatchResolutionTest :
    FunSpec({
        beforeTest { Dispatchers.setMain(StandardTestDispatcher()) }
        afterTest { Dispatchers.resetMain() }

        test("Match details for books and people, and the receipt, resolve, sharing one receipt store") {
            val app =
                koinApplication {
                    modules(
                        bookPresentationModule,
                        module {
                            single<BookRepository> { FakeBookRepository() }
                            single<MatchingRepository> { FakeMatchingRepository() }
                            single<ContributorRepository> { FakeContributorRepository() }
                            single { ErrorBus() }
                        },
                    )
                }
            val match = app.koin.get<BookMatchViewModel> { parametersOf("book-1") }
            val receipt = app.koin.get<MatchReceiptViewModel> { parametersOf("book-1") }
            val person = app.koin.get<PersonMatchViewModel> { parametersOf("person-1") }
            person.shouldNotBeNull()
            (
                app.koin.get<MatchReceiptStore>() ===
                    app.koin.get<MatchReceiptStore>()
            ) shouldBe true
            match.shouldNotBeNull()
            receipt.shouldNotBeNull()
            app.close()
        }
    })
