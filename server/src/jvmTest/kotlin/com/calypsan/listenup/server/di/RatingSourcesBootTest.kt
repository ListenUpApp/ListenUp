package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import com.calypsan.listenup.server.metadata.spi.RatingSource
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.ktor.server.testing.testApplication
import org.koin.ktor.ext.get as koinGet

/**
 * Boots the real `Application.module()` and reads the metadata registry the ratings fetcher walks:
 * every outside source the design names must be registered there, or it is never fetched and never
 * shown on the admin's source list.
 */
class RatingSourcesBootTest :
    FunSpec({
        test("the booted server rates books from Audible and Hardcover") {
            testApplication {
                useIsolatedTestConfig()
                application {
                    module()
                    koinGet<MetadataProviderRegistry>()
                        .capable<RatingSource>()
                        .map { it.ratingSource } shouldContainExactlyInAnyOrder
                        listOf(ExternalRatingSource.AUDIBLE, ExternalRatingSource.HARDCOVER)
                }
                startApplication()
            }
        }
    })
