package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.local.db.ReadingOrderBookDao
import com.calypsan.listenup.client.data.local.db.ReadingOrderDao
import com.calypsan.listenup.client.data.local.db.ReadingOrderFollowDao
import com.calypsan.listenup.client.data.local.db.UserDao
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.RpcAuthRecovery
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [readingOrderModule]. Per the architecture rubric every leaf Koin
 * module is covered by a `module.verify()` test in commonTest. The whitelist
 * enumerates dependencies the reading-order bindings pull in but other modules own:
 *
 *  - [ApiClientFactory] / [RpcAuthRecovery] — owned by `networkModule`.
 *  - [ServerConfig] — owned by `settingsModule`.
 *  - [ReadingOrderDao] / [ReadingOrderBookDao] / [ReadingOrderFollowDao] / [UserDao]
 *    — owned by `persistenceModule`.
 *  - [OfflineEditor] — owned by `clientSyncModule`.
 *  - [AuthSession] — owned by `clientAuthModule`.
 */
@OptIn(KoinExperimentalAPI::class)
class ReadingOrderModuleVerifyTest :
    FunSpec({

        test("readingOrderModule wires up against its declared external dependencies") {
            readingOrderModule.verify(
                extraTypes =
                    listOf(
                        ReadingOrderDao::class,
                        ReadingOrderBookDao::class,
                        ReadingOrderFollowDao::class,
                        UserDao::class,
                        ApiClientFactory::class,
                        ServerConfig::class,
                        RpcAuthRecovery::class,
                        OfflineEditor::class,
                        AuthSession::class,
                    ),
            )
        }
    })
