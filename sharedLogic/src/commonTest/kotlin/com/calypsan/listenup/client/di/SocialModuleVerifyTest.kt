package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.local.db.ActivityDao
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.PublicProfileDao
import com.calypsan.listenup.client.data.local.db.UserDao
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.AuthRpcFactory
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.PresenceRefreshSignal
import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [socialModule]. Per the architecture rubric every leaf Koin module is
 * covered by a `module.verify()` test in commonTest. The whitelist enumerates dependencies
 * the social bindings pull in but other modules own:
 *
 *  - [ApiClientFactory] — owned by `networkModule`.
 *  - [ServerConfig] — owned by `settingsModule`.
 *  - [UserDao] — owned by `persistenceModule`.
 *  - [ActivityDao] — owned by `persistenceModule`.
 *  - [BookDao] — owned by `persistenceModule`.
 *  - [PublicProfileDao] — owned by `persistenceModule`.
 *  - [ImageStorage] — owned by the platform storage module.
 *  - [PresenceRefreshSignal] — owned by `clientSyncModule`.
 *  - [OfflineEditor] — owned by `clientSyncModule`.
 *  - [AuthRpcFactory] — owned by `clientAuthModule`.
 *  - [PlaybackPositionRepository] — owned by `listeningModule`.
 */
@OptIn(KoinExperimentalAPI::class)
class SocialModuleVerifyTest :
    FunSpec({

        test("socialModule wires up against its declared external dependencies") {
            socialModule.verify(
                extraTypes =
                    listOf(
                        UserDao::class,
                        ActivityDao::class,
                        BookDao::class,
                        PublicProfileDao::class,
                        ImageStorage::class,
                        PresenceRefreshSignal::class,
                        OfflineEditor::class,
                        AuthRpcFactory::class,
                        PlaybackPositionRepository::class,
                        ApiClientFactory::class,
                        ServerConfig::class,
                    ),
            )
        }
    })
