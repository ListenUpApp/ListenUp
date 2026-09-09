package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.usecase.shelf.CreateShelfUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.DeleteShelfUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.LoadShelfDetailUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.RemoveBookFromShelfUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.ReorderShelfBooksUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.UpdateShelfUseCase
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [shelfPresentationModule].
 *
 * The whitelist enumerates dependencies this module pulls in but other modules own:
 *
 *  - [LoadShelfDetailUseCase] — owned by `shelfModule`.
 *  - [RemoveBookFromShelfUseCase] — owned by `shelfModule`.
 *  - [ReorderShelfBooksUseCase] — owned by `shelfModule`.
 *  - [CreateShelfUseCase] — owned by `shelfModule`.
 *  - [UpdateShelfUseCase] — owned by `shelfModule`.
 *  - [DeleteShelfUseCase] — owned by `shelfModule`.
 *  - [ShelfRepository] — owned by `shelfModule`.
 *  - [ErrorBus] — owned by `appCoreModule`.
 */
@OptIn(KoinExperimentalAPI::class)
class ShelfPresentationModuleVerifyTest :
    FunSpec({

        test("shelfPresentationModule wires up against its declared external dependencies") {
            shelfPresentationModule.verify(
                extraTypes =
                    listOf(
                        LoadShelfDetailUseCase::class,
                        RemoveBookFromShelfUseCase::class,
                        ReorderShelfBooksUseCase::class,
                        CreateShelfUseCase::class,
                        UpdateShelfUseCase::class,
                        DeleteShelfUseCase::class,
                        ShelfRepository::class,
                        ErrorBus::class,
                    ),
            )
        }
    })
