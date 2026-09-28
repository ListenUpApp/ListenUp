package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.local.db.BookRatingDao
import com.calypsan.listenup.client.data.local.db.ShelfDao
import com.calypsan.listenup.client.data.local.db.UserDao
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [shelfModule]. Per the architecture rubric every leaf Koin module is
 * covered by a `module.verify()` test in commonTest. The whitelist enumerates dependencies
 * the shelf bindings pull in but other modules own:
 *
 *  - [ApiClientFactory] — owned by `networkModule`.
 *  - [ServerConfig] — owned by `settingsModule`.
 *  - [ShelfDao] — owned by `persistenceModule`.
 *  - [UserDao] — owned by `persistenceModule`.
 *  - [ImageRepository] — owned by `mediaModule`.
 *  - [BookRatingDao] — owned by `persistenceModule` ([BookRatingRepository][com.calypsan.listenup.client.domain.repository.BookRatingRepository] is piggy-backed here, see [shelfModule]'s KDoc).
 *  - [OfflineEditor] — owned by `clientSyncModule`.
 *  - [AuthSession] — owned by `clientAuthModule`.
 */
@OptIn(KoinExperimentalAPI::class)
class ShelfModuleVerifyTest :
    FunSpec({

        test("shelfModule wires up against its declared external dependencies") {
            shelfModule.verify(
                extraTypes =
                    listOf(
                        ShelfDao::class,
                        UserDao::class,
                        ImageRepository::class,
                        ApiClientFactory::class,
                        ServerConfig::class,
                        com.calypsan.listenup.client.data.remote.RpcAuthRecovery::class,
                        BookRatingDao::class,
                        OfflineEditor::class,
                        AuthSession::class,
                    ),
            )
        }
    })
