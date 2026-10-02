package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.admin.HardcoverApiTokenStatus
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.test.runTest

private const val TOKEN = "hc_admin_test_token_abc123"
private const val OTHER = "hc_admin_test_token_def456"

private fun cipher(secret: String = "secret") = HardcoverTokenCipher(HardcoverTokenCipher.deriveKey(secret))

/** The admin's Hardcover API token at rest (#1542): sealed, write-only, rejected only when it is still the one stored. */
class HardcoverApiTokenStoreTest :
    FunSpec({
        test("nothing stored reads as NotSet and is never usable") {
            withSqlDatabase {
                runTest {
                    val store = HardcoverApiTokenStore(sql, cipher())
                    store.status() shouldBe HardcoverApiTokenStatus.NotSet
                    store.usable().shouldBeNull()
                }
            }
        }

        test("a saved token is sealed in the row, described only by its owner, and handed back only to reads") {
            withSqlDatabase {
                runTest {
                    val store = HardcoverApiTokenStore(sql, cipher())
                    store.save(TOKEN, "simon")

                    val row = sql.hardcoverApiTokenQueries.selectToken().executeAsOne()
                    row.token_enc shouldNotContain TOKEN
                    row.hc_username shouldBe "simon"
                    val status = store.status()
                    (status as HardcoverApiTokenStatus.Saved).username shouldBe "simon"
                    status.toString() shouldNotContain TOKEN
                    val usable = store.usable().shouldNotBeNull()
                    usable.token shouldBe TOKEN
                    usable.username shouldBe "simon"
                    usable.toString() shouldNotContain TOKEN
                }
            }
        }

        test("a rejection marks the stored token, which then reads as Rejected and is no longer usable") {
            withSqlDatabase {
                runTest {
                    val store = HardcoverApiTokenStore(sql, cipher())
                    store.save(TOKEN, "simon")

                    store.markRejected(TOKEN) shouldBe true

                    store.status() shouldBe HardcoverApiTokenStatus.Rejected("simon")
                    store.usable().shouldBeNull()
                    store.markRejected(TOKEN) shouldBe false
                }
            }
        }

        test("a token the admin has since replaced is never blamed for the old one's 401") {
            withSqlDatabase {
                runTest {
                    val store = HardcoverApiTokenStore(sql, cipher())
                    store.save(TOKEN, "simon")
                    store.save(OTHER, "simon2")

                    store.markRejected(TOKEN) shouldBe false

                    (store.status() as HardcoverApiTokenStatus.Saved).username shouldBe "simon2"
                    store.usable()?.token shouldBe OTHER
                }
            }
        }

        test("saving again clears an earlier rejection") {
            withSqlDatabase {
                runTest {
                    val store = HardcoverApiTokenStore(sql, cipher())
                    store.save(TOKEN, "simon")
                    store.markRejected(TOKEN)

                    store.save(OTHER, "simon")

                    store.status().shouldBeSaved("simon")
                    store.usable()?.token shouldBe OTHER
                }
            }
        }

        test("a token sealed under another server's secret reads as Rejected and is never usable") {
            withSqlDatabase {
                runTest {
                    HardcoverApiTokenStore(sql, cipher("old-secret")).save(TOKEN, "simon")
                    val restored = HardcoverApiTokenStore(sql, cipher("new-secret"))

                    restored.status() shouldBe HardcoverApiTokenStatus.Rejected("simon")
                    restored.usable().shouldBeNull()
                }
            }
        }

        test("clear removes it") {
            withSqlDatabase {
                runTest {
                    val store = HardcoverApiTokenStore(sql, cipher())
                    store.save(TOKEN, "simon")

                    store.clear()

                    store.status() shouldBe HardcoverApiTokenStatus.NotSet
                    sql.hardcoverApiTokenQueries.selectToken().executeAsOneOrNull().shouldBeNull()
                }
            }
        }
    })

private fun HardcoverApiTokenStatus.shouldBeSaved(username: String) {
    (this as HardcoverApiTokenStatus.Saved).username shouldBe username
}
