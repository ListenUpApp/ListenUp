package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.domain.repository.BookEditRepository
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.MoodRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditViewModel
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.MockMode
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Proves the bulk editor can actually be built from the container.
 *
 * [BookPresentationModuleVerifyTest] cannot do this. Koin's `verify()` reflects over a definition's
 * primary constructor, and [BulkEditViewModel]'s is `internal` — it has to be, because it takes the
 * `internal` `BulkEditApplier`, and a public constructor cannot expose an internal type. Reflection
 * skips it, so `verify()` passes whether or not the applier is registered at all. That was measured,
 * not assumed: deleting the applier factory left `verify()` green.
 *
 * So this resolves the ViewModel for real. It is the only thing standing between a missing
 * registration and a crash the first time a user taps Bulk Edit.
 */
class BulkEditResolutionTest :
    FunSpec({

        // viewModelScope needs a Main dispatcher; without one the load coroutine
        // fails on construction and buries the resolution result under coroutine noise.
        beforeTest { Dispatchers.setMain(StandardTestDispatcher()) }

        afterTest { Dispatchers.resetMain() }

        test("the bulk editor resolves from the container with its selection") {
            val app =
                koinApplication {
                    modules(
                        bookPresentationModule,
                        module {
                            single<BookRepository> { mock(MockMode.autoUnit) }
                            single<BookEditRepository> { mock(MockMode.autoUnit) }
                            single<TagRepository> { mock(MockMode.autoUnit) }
                            single<MoodRepository> { mock(MockMode.autoUnit) }
                            single { ErrorBus() }
                        },
                    )
                }

            val viewModel =
                app.koin.get<BulkEditViewModel> { parametersOf(listOf("book-1", "book-2")) }

            viewModel.shouldNotBeNull()
            viewModel.close()
        }
    })
