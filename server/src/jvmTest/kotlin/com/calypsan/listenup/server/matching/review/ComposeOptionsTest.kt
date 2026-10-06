package com.calypsan.listenup.server.matching.review

import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.metadata.CoreFailure
import com.calypsan.listenup.server.metadata.EnrichmentCoordinator
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.metadata.spi.CoverMeta
import com.calypsan.listenup.server.metadata.spi.EnrichmentRoutes
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainKeys
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds

private fun coordinator(vararg providers: FakeCatalogProvider) =
    EnrichmentCoordinator(MetadataProviderRegistry(providers.toList()), EnrichmentRoutes.DEFAULT)

class ComposeOptionsTest :
    FunSpec({
        test("composeOptions keeps every provider's value, never collapsing them") {
            runTest {
                val audible = FakeCatalogProvider(AUDIBLE, core = AppResult.Success(core(description = "A")))
                val hardcover =
                    FakeCatalogProvider(
                        HARDCOVER,
                        core = AppResult.Success(core(description = "H")),
                        covers = listOf(CoverMeta("https://h/c.jpg", sourceKey = "1")),
                    )
                val options = coordinator(audible, hardcover).composeOptions(BookIdentity(asin = "B", title = "T"), MetadataLocale("us"))
                options.cores.mapValues { it.value.description } shouldBe mapOf(AUDIBLE to "A", HARDCOVER to "H")
                options.covers.shouldContainKeys(HARDCOVER)
            }
        }

        test("composeBook is the first option per field over composeOptions") {
            runTest {
                val audible = FakeCatalogProvider(AUDIBLE, core = AppResult.Success(core(description = "A")))
                val hardcover = FakeCatalogProvider(HARDCOVER, core = AppResult.Success(core(description = "H", publisher = "P")))
                val composed =
                    (coordinator(audible, hardcover).composeBook(BookIdentity(asin = "B", title = "T"), MetadataLocale("us"))
                        as AppResult.Success).data!!
                composed.core.description shouldBe "A"
                composed.core.publisher shouldBe "P"
            }
        }

        test("failures are typed per provider: a deadline is a timeout, a 429 keeps its retry-after") {
            runTest {
                val slow = FakeCatalogProvider(AUDIBLE, core = AppResult.Success(core(title = "x")), slow = 20.seconds)
                val limited = FakeCatalogProvider(HARDCOVER, core = AppResult.Failure(MetadataError.ExternalRateLimited(retryAfterSeconds = 5)))
                val options =
                    coordinator(slow, limited).composeOptions(BookIdentity(title = "T"), MetadataLocale("us"), deadline = 8.seconds)
                options.coreFailures shouldBe mapOf(AUDIBLE to CoreFailure.TimedOut, HARDCOVER to CoreFailure.RateLimited(5))
            }
        }
    })
