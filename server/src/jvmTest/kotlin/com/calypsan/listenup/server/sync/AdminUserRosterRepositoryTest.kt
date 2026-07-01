package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.AdminUserRosterSyncPayload
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

private fun row(id: String) =
    AdminUserRosterSyncPayload(
        id = id,
        email = "$id@example.com",
        displayName = "Alice",
        role = "MEMBER",
        status = "ACTIVE",
        canShare = true,
        accountCreatedAt = 1_000L,
        revision = 0,
        updatedAt = 0,
        createdAt = 0,
        deletedAt = null,
    )

class AdminUserRosterRepositoryTest :
    FunSpec({
        test("upsert persists a roster row and pullSince returns it") {
            withSqlDatabase {
                val repo = AdminUserRosterRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    repo.upsert(row("user-1"))

                    val page = repo.pullSince(userId = "admin-1", cursor = 0, limit = 100)
                    page.items.map { item -> item.id } shouldBe listOf("user-1")
                    page.items shouldHaveSize 1
                }
            }
        }
    })
