package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.local.images.StoragePaths
import io.kotest.core.spec.style.FunSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.io.files.Path
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [loggingModule]. Per the architecture rubric every leaf
 * Koin module is covered by a `module.verify()` test in commonTest.
 *
 * [StoragePaths] comes from the platform storage module; the writer dispatcher,
 * directory path and size/capacity knobs are plain constructor values, so they
 * are whitelisted as externally provided.
 */
@OptIn(KoinExperimentalAPI::class)
class LoggingModuleVerifyTest :
    FunSpec({

        test("loggingModule wires up against its declared external dependencies") {
            loggingModule.verify(
                extraTypes =
                    listOf(
                        StoragePaths::class,
                        Path::class,
                        CoroutineDispatcher::class,
                        Long::class,
                        Int::class,
                    ),
            )
        }
    })
