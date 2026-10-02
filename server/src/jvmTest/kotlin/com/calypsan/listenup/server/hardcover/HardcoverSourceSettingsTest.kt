package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.admin.HardcoverApiTokenStatus
import com.calypsan.listenup.api.dto.admin.HardcoverSourceStatus
import com.calypsan.listenup.api.dto.admin.RatingSourceUnavailable
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.error.AdminError
import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

private const val WRONG_TOKEN = "hc_wrong_test_token_zzz999"

/** Counts every wait, so a test can prove the `me` check is paced like every other Hardcover call. */
private class CountingLimiter : HardcoverRateLimiter() {
    var waits = 0

    override suspend fun await() {
        waits++
    }
}

private class SettingsRig(
    rig: HardcoverCatalogRig,
) {
    val hardcover = FakeHardcoverCatalog().apply { accounts = mapOf(ADMIN_TOKEN to "simon") }
    val limiter = CountingLimiter()
    val apiTokens = rig.apiTokens
    val settings =
        HardcoverSourceSettings(
            apiTokens = rig.apiTokens,
            catalogToken = rig.catalog,
            graphQl = hardcover.client(),
            rateLimiter = limiter,
            settings = ServerSettingsRepository(rig.sql, RegistrationPolicy.OPEN),
        )
}

private fun settingsTest(block: suspend SettingsRig.(HardcoverCatalogRig) -> Unit) =
    withSqlDatabase {
        runTest {
            val rig = HardcoverCatalogRig(sql)
            SettingsRig(rig).block(rig)
        }
    }

/** Admin → Hardcover on the server (#1542): the token is checked before it is stored, and never handed back. */
class HardcoverSourceSettingsTest :
    FunSpec({
        test("a fresh server has no token, metadata on, and nothing to read with") {
            settingsTest {
                settings.status() shouldBe
                    HardcoverSourceStatus(
                        apiToken = HardcoverApiTokenStatus.NotSet,
                        metadataEnabled = true,
                        metadataUnavailable = RatingSourceUnavailable.NO_CONNECTION,
                    )
            }
        }

        test("a token Hardcover accepts is checked with `me`, paced, stored sealed, and described by its owner only") {
            settingsTest {
                val result = settings.setApiToken(ADMIN_TOKEN)

                val status = result.shouldBeInstanceOf<AppResult.Success<HardcoverSourceStatus>>().data
                (status.apiToken as HardcoverApiTokenStatus.Saved).username shouldBe "simon"
                status.metadataUnavailable.shouldBeNull()
                status.toString() shouldNotContain ADMIN_TOKEN
                hardcover.asked.single() shouldBe FakeHardcoverCatalog.Asked("me", ADMIN_TOKEN)
                limiter.waits shouldBe 1
                apiTokens.usable()?.token shouldBe ADMIN_TOKEN
            }
        }

        test("a pasted 'Bearer ' prefix and surrounding spaces are not part of the token") {
            settingsTest {
                settings.setApiToken("  Bearer $ADMIN_TOKEN \n").shouldBeInstanceOf<AppResult.Success<HardcoverSourceStatus>>()

                hardcover.asked.single().token shouldBe ADMIN_TOKEN
                apiTokens.usable()?.token shouldBe ADMIN_TOKEN
            }
        }

        test("a token Hardcover refuses is a typed error, and nothing is stored") {
            settingsTest {
                hardcover.rejected = setOf(WRONG_TOKEN)

                val error = settings.setApiToken(WRONG_TOKEN).shouldBeInstanceOf<AppResult.Failure>().error

                error.shouldBeInstanceOf<HardcoverError.TokenRejected>()
                error.toString() shouldNotContain WRONG_TOKEN
                settings.status().apiToken shouldBe HardcoverApiTokenStatus.NotSet
            }
        }

        test("an unreachable Hardcover stores nothing and says so, without the token") {
            settingsTest {
                hardcover.unavailable = true

                val error = settings.setApiToken(ADMIN_TOKEN).shouldBeInstanceOf<AppResult.Failure>().error

                error.shouldBeInstanceOf<HardcoverError.Unavailable>()
                (error.debugInfo ?: "") shouldNotContain ADMIN_TOKEN
                settings.status().apiToken shouldBe HardcoverApiTokenStatus.NotSet
            }
        }

        test("a blank or absurdly long token is refused before Hardcover is asked") {
            settingsTest {
                settings
                    .setApiToken("   ")
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<AdminError.InvalidInput>()
                settings.setApiToken("x".repeat(5_000)).shouldBeInstanceOf<AppResult.Failure>()
                hardcover.asked shouldBe emptyList()
            }
        }

        test("Remove clears the token, and the status says so") {
            settingsTest {
                settings.setApiToken(ADMIN_TOKEN)

                settings.clearApiToken().apiToken shouldBe HardcoverApiTokenStatus.NotSet
                apiTokens.usable().shouldBeNull()
            }
        }

        test("the metadata switch round-trips, and a connected account makes Hardcover available without a token") {
            settingsTest { rig ->
                settings.setMetadataEnabled(false).metadataEnabled shouldBe false
                settings.metadataEnabled() shouldBe false
                settings.setMetadataEnabled(true).metadataEnabled shouldBe true
                rig.connect("member")
                settings.status().metadataUnavailable.shouldBeNull()
                settings.accountName() shouldBe "hc-member"
            }
        }
    })
