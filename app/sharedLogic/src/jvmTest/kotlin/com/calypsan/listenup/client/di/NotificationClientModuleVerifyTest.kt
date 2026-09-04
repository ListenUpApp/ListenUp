package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.local.db.NotificationDao
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [notificationClientModule]. Per the architecture rubric every leaf Koin module
 * is covered by a `module.verify()` test in commonTest. The whitelist enumerates dependencies
 * the notification bindings pull in but other modules own:
 *
 *  - [ApiClientFactory] — owned by `networkModule`.
 *  - [ServerConfig] — owned by `settingsModule`.
 *  - [NotificationDao] — owned by `persistenceModule`.
 *  - [OfflineEditor] — owned by `clientSyncModule`.
 */
@OptIn(KoinExperimentalAPI::class)
class NotificationClientModuleVerifyTest :
    FunSpec({

        test("notificationClientModule wires up against its declared external dependencies") {
            notificationClientModule.verify(
                extraTypes =
                    listOf(
                        NotificationDao::class,
                        OfflineEditor::class,
                        ApiClientFactory::class,
                        ServerConfig::class,
                    ),
            )
        }
    })
