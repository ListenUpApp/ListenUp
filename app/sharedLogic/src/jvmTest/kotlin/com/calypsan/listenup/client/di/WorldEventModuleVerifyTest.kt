package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.local.db.EntityDao
import com.calypsan.listenup.client.data.local.db.WorldEventDao
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.RpcAuthRecovery
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [worldEventModule]. The whitelist enumerates dependencies its bindings pull in but other
 * modules own:
 *
 *  - [WorldEventDao], [EntityDao] — owned by `persistenceModule`; the entity mirror backs the offline kind checks.
 *  - [OfflineEditor] — owned by `clientSyncModule`.
 *  - [ApiClientFactory], [RpcAuthRecovery] — owned by `networkModule`; the authed channel and its recovery.
 *  - [ServerConfig] — owned by `settingsModule`.
 *  - [AuthSession] — owned by `authModule`; names the user whose DELETE an undo may revert.
 */
@OptIn(KoinExperimentalAPI::class)
class WorldEventModuleVerifyTest :
    FunSpec({
        test("worldEventModule wires up against its declared external dependencies") {
            worldEventModule.verify(
                extraTypes =
                    listOf(
                        WorldEventDao::class,
                        EntityDao::class,
                        OfflineEditor::class,
                        ApiClientFactory::class,
                        ServerConfig::class,
                        RpcAuthRecovery::class,
                        AuthSession::class,
                    ),
            )
        }
    })
