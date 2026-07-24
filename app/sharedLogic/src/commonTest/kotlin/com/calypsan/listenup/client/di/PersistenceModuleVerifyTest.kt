package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [persistenceModule]. Per the architecture rubric every leaf
 * Koin module is covered by a `module.verify()` test in commonTest.
 *
 * The whitelist enumerates dependencies the persistence bindings pull in but
 * other modules own:
 *
 *  - [ListenUpDatabase] — owned by `platformDatabaseModule`. Every DAO binding
 *    calls an accessor on this singleton; [RoomTransactionRunner] also depends on it.
 */
@OptIn(KoinExperimentalAPI::class)
class PersistenceModuleVerifyTest :
    FunSpec({

        test("persistenceModule wires up against its declared external dependencies") {
            persistenceModule.verify(
                extraTypes =
                    listOf(
                        ListenUpDatabase::class,
                    ),
            )
        }
    })
