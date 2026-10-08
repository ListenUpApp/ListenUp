package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.local.db.EntityDao
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.RpcAuthRecovery
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [entityModule]. The whitelist enumerates dependencies the entity bindings pull in but
 * other modules own:
 *
 *  - [EntityDao] — owned by `persistenceModule`.
 *  - [OfflineEditor] — owned by `clientSyncModule`.
 *  - [ApiClientFactory] — owned by `networkModule`.
 *  - [ServerConfig] — owned by `settingsModule`.
 *  - [RpcAuthRecovery] — owned by `networkModule`; the authed channel's recovery.
 *  - [AuthSession] — owned by `authModule`; names the user whose DELETE an undo may revert.
 */
@OptIn(KoinExperimentalAPI::class)
class EntityModuleVerifyTest :
    FunSpec({
        test("entityModule wires up against its declared external dependencies") {
            entityModule.verify(
                extraTypes =
                    listOf(
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
