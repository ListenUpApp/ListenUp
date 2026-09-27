package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [hardcoverClientModule]. Per the architecture rubric every leaf Koin module is
 * covered by a `module.verify()` test. The whitelist enumerates dependencies the Hardcover bindings
 * pull in but other modules own:
 *
 *  - [ApiClientFactory] — owned by `networkModule`.
 *  - [ServerConfig] — owned by `settingsModule`.
 */
@OptIn(KoinExperimentalAPI::class)
class HardcoverClientModuleVerifyTest :
    FunSpec({

        test("hardcoverClientModule wires up against its declared external dependencies") {
            hardcoverClientModule.verify(
                extraTypes =
                    listOf(
                        ApiClientFactory::class,
                        ServerConfig::class,
                    ),
            )
        }
    })
