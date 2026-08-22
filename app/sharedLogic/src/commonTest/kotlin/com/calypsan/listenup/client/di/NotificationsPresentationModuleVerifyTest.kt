package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.domain.repository.NotificationRepository
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [notificationsPresentationModule]. Per the architecture rubric every leaf Koin
 * module is covered by a `module.verify()` test in commonTest. The whitelist enumerates
 * dependencies the notification ViewModels pull in but other modules own:
 *
 *  - [NotificationRepository] — owned by `notificationClientModule`.
 *  - [ErrorBus] — owned by `appCoreModule`.
 */
@OptIn(KoinExperimentalAPI::class)
class NotificationsPresentationModuleVerifyTest :
    FunSpec({

        test("notificationsPresentationModule wires up against its declared external dependencies") {
            notificationsPresentationModule.verify(
                extraTypes =
                    listOf(
                        NotificationRepository::class,
                        ErrorBus::class,
                    ),
            )
        }
    })
