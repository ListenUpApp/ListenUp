package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.local.db.BookTagDao
import com.calypsan.listenup.client.data.local.db.GenreDao
import com.calypsan.listenup.client.data.local.db.MoodDao
import com.calypsan.listenup.client.data.local.db.TagDao
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [genreTagModule]. Per the architecture rubric every leaf Koin module is
 * covered by a `module.verify()` test in commonTest. The whitelist enumerates dependencies
 * the genre and tag bindings pull in but other modules own:
 *
 *  - [ApiClientFactory] — owned by `networkModule`.
 *  - [ServerConfig] — owned by `settingsModule`.
 *  - [GenreDao] — owned by `persistenceModule`.
 *  - [TagDao] — owned by `persistenceModule`.
 *  - [BookTagDao] — owned by `persistenceModule`.
 *  - [MoodDao] — owned by `persistenceModule`.
 */
@OptIn(KoinExperimentalAPI::class)
class GenreTagModuleVerifyTest :
    FunSpec({

        test("genreTagModule wires up against its declared external dependencies") {
            genreTagModule.verify(
                extraTypes =
                    listOf(
                        GenreDao::class,
                        TagDao::class,
                        BookTagDao::class,
                        MoodDao::class,
                        ApiClientFactory::class,
                        ServerConfig::class,
                    ),
            )
        }
    })
