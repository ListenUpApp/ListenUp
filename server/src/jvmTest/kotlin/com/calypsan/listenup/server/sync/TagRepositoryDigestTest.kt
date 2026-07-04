package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.test.runTest

class TagRepositoryDigestTest :
    FunSpec({

        test("empty domain digest is count=0, hash=empty") {
            withSqlDatabase {
                val repo = TagRepository(sql, ChangeBus(), SyncRegistry())
                runTest {
                    val d = repo.digest(userId = null, cursor = Long.MAX_VALUE)
                    d.cursor shouldBe Long.MAX_VALUE
                    d.count shouldBe 0
                    d.hash shouldBe ""
                }
            }
        }

        test("digest is deterministic and sha256-prefixed") {
            withSqlDatabase {
                val repo = TagRepository(sql, ChangeBus(), SyncRegistry())
                runTest {
                    repo.upsert(Tag("a", "alpha", "alpha", 0, 0))
                    repo.upsert(Tag("b", "beta", "beta", 0, 0))
                    val d1 = repo.digest(userId = null, cursor = Long.MAX_VALUE)
                    val d2 = repo.digest(userId = null, cursor = Long.MAX_VALUE)
                    d1 shouldBe d2
                    d1.count shouldBe 2
                    d1.hash shouldStartWith "sha256:"
                    d1.hash.length shouldBe "sha256:".length + 64 // 64 hex chars
                }
            }
        }

        test("digest changes when a row is updated") {
            withSqlDatabase {
                val repo = TagRepository(sql, ChangeBus(), SyncRegistry())
                runTest {
                    repo.upsert(Tag("a", "alpha", "alpha", 0, 0))
                    val before = repo.digest(userId = null, cursor = Long.MAX_VALUE)
                    repo.upsert(Tag("a", "alpha-updated", "alpha-updated", 0, 0))
                    val after = repo.digest(userId = null, cursor = Long.MAX_VALUE)
                    (before.hash != after.hash) shouldBe true
                    before.count shouldBe after.count // still one row
                }
            }
        }

        test("digest scopes by cursor") {
            withSqlDatabase {
                val repo = TagRepository(sql, ChangeBus(), SyncRegistry())
                runTest {
                    repo.upsert(Tag("a", "alpha", "alpha", 0, 0)) // rev 1
                    repo.upsert(Tag("b", "beta", "beta", 0, 0)) // rev 2
                    repo.upsert(Tag("c", "gamma", "gamma", 0, 0)) // rev 3
                    val d = repo.digest(userId = null, cursor = 2L)
                    d.count shouldBe 2 // only a, b
                }
            }
        }

        test("digest EXCLUDES soft-deleted rows (F1: symmetric with the client's tombstone-excluding digest)") {
            withSqlDatabase {
                val repo = TagRepository(sql, ChangeBus(), SyncRegistry())
                runTest {
                    repo.upsert(Tag("a", "alpha", "alpha", 0, 0))
                    repo.upsert(Tag("b", "beta", "beta", 0, 0))
                    repo.softDelete("a")
                    // The digest counts LIVE rows only — the tombstoned "a" drops out, leaving "b".
                    // This is what lets a client that tombstoned "a" locally converge instead of
                    // drifting forever (the deletion still reaches clients via catch-up / firehose).
                    val d = repo.digest(userId = null, cursor = Long.MAX_VALUE)
                    d.count shouldBe 1
                }
            }
        }

        test("digest byte format is the canonical wire contract") {
            withSqlDatabase {
                val repo = TagRepository(sql, ChangeBus(), SyncRegistry())
                runTest {
                    repo.upsert(Tag("a", "alpha", "alpha", 0, 0)) // revision 1
                    repo.upsert(Tag("b", "beta", "beta", 0, 0)) // revision 2
                    // Canonical layout: <id>|<revision>\n per row, sorted by id, trailing \n
                    // Input bytes: "a|1\nb|2\n"
                    val expectedInput = "a|1\nb|2\n"
                    val md = java.security.MessageDigest.getInstance("SHA-256")
                    val expectedHex =
                        md
                            .digest(expectedInput.toByteArray(Charsets.UTF_8))
                            .joinToString("") { "%02x".format(it) }
                    val actual = repo.digest(userId = null, cursor = Long.MAX_VALUE)
                    actual.hash shouldBe "sha256:$expectedHex"
                }
            }
        }
    })
