package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.admin.HardcoverApiTokenStatus
import com.calypsan.listenup.api.dto.hardcover.HardcoverBrokenReason
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

private fun catalogTest(block: suspend HardcoverCatalogRig.() -> Unit) = withSqlDatabase { runTest { HardcoverCatalogRig(sql).block() } }

/** Which token reads Hardcover's catalogue (#1542): the admin's API token, then a borrowed connected account. */
class HardcoverCatalogTokenTest :
    FunSpec({
        test("with no API token and nobody connected there is no token, and nobody is asked") {
            catalogTest {
                val asked = mutableListOf<String>()

                catalog.read { token -> HardcoverCall.Ok(token).also { asked += token } }.shouldBeNull()

                asked shouldBe emptyList()
                catalog.isAvailable() shouldBe false
                catalog.accountName().shouldBeNull()
            }
        }

        test("with no API token, a connected account reads, as ratings always did") {
            catalogTest {
                connect("member")

                catalog.read { token -> HardcoverCall.Ok(token) } shouldBe HardcoverCall.Ok("at-member")
                catalog.accountName() shouldBe "hc-member"
            }
        }

        test("the API token reads first, even with the server's admin connected") {
            catalogTest {
                connect("admin", UserRoleColumn.ADMIN)
                saveAdminToken()

                catalog.read { token -> HardcoverCall.Ok(token) } shouldBe HardcoverCall.Ok(ADMIN_TOKEN)
                catalog.apiToken() shouldBe ADMIN_TOKEN
                catalog.accountName() shouldBe "simon"
                catalog.isAvailable() shouldBe true
            }
        }

        test("a 401 on the API token marks it rejected, and the same read falls back to a connected account") {
            catalogTest {
                connect("admin", UserRoleColumn.ADMIN)
                saveAdminToken()
                val asked = mutableListOf<String>()

                val answer =
                    catalog.read { token ->
                        asked += token
                        if (token == ADMIN_TOKEN) HardcoverCall.Unauthorized else HardcoverCall.Ok("rating")
                    }

                answer shouldBe HardcoverCall.Ok("rating")
                asked shouldBe listOf(ADMIN_TOKEN, "at-admin")
                apiTokens.status() shouldBe HardcoverApiTokenStatus.Rejected("simon")
                catalog.apiToken().shouldBeNull()
                catalog.accountName() shouldBe "hc-admin"
            }
        }

        test("once rejected, the API token is not tried again") {
            catalogTest {
                connect("member")
                saveAdminToken()
                apiTokens.markRejected(ADMIN_TOKEN)
                val asked = mutableListOf<String>()

                catalog.read { token -> HardcoverCall.Ok(token).also { asked += token } }

                asked shouldBe listOf("at-member")
            }
        }

        test("a 401 on the API token with nobody connected marks it and reads nothing") {
            catalogTest {
                saveAdminToken()

                catalog.read<String> { HardcoverCall.Unauthorized }.shouldBeNull()

                apiTokens.status() shouldBe HardcoverApiTokenStatus.Rejected("simon")
            }
        }

        test("a broken connection doesn't count: with nobody healthy, nothing is read") {
            catalogTest {
                connect("member")
                connections.markBroken("member", HardcoverBrokenReason.REVOKED)

                catalog.isAvailable() shouldBe false
                catalog.read { token -> HardcoverCall.Ok(token) }.shouldBeNull()
            }
        }

        test("any other failure on the API token is returned as it is, and the token stays trusted") {
            catalogTest {
                connect("member")
                saveAdminToken()

                catalog.read<String> { HardcoverCall.Failed("books 502") }.shouldBeInstanceOf<HardcoverCall.Failed>()

                (apiTokens.status() is HardcoverApiTokenStatus.Saved) shouldBe true
            }
        }
    })
