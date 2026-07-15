package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.local.db.BioEntryDao
import com.calypsan.listenup.client.data.local.db.EntityDao
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [entityModule]. Per the architecture rubric every leaf Koin module is
 * covered by a `module.verify()` test in commonTest — this is the Story World entity
 * aggregate's. The whitelist enumerates dependencies the entity bindings pull in but other
 * modules own:
 *
 *  - [ApiClientFactory] — owned by `networkModule`.
 *  - [ServerConfig] — owned by `settingsModule`.
 *  - [EntityDao] / [BioEntryDao] — owned by `persistenceModule`.
 *  - [OfflineEditor] — owned by `clientSyncModule`.
 */
@OptIn(KoinExperimentalAPI::class)
class EntityModuleVerifyTest :
    FunSpec({

        test("entityModule wires up against its declared external dependencies") {
            entityModule.verify(
                extraTypes =
                    listOf(
                        ApiClientFactory::class,
                        ServerConfig::class,
                        EntityDao::class,
                        BioEntryDao::class,
                        OfflineEditor::class,
                    ),
            )
        }
    })
