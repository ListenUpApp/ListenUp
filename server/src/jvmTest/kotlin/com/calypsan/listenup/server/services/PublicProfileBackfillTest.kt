package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlinx.coroutines.test.runTest

class PublicProfileBackfillTest :
    FunSpec({
        test("backfillAll creates a projection row per active user") {
            withSqlDatabase {
                sql.transaction {
                    sql.usersQueries.insert(
                        id = "u1",
                        email = "ada@example.com",
                        email_normalized = "ada@example.com",
                        password_hash = "phc",
                        role = "MEMBER",
                        display_name = "Ada",
                        status = "ACTIVE",
                        created_at = 1L,
                        updated_at = 1L,
                        last_login_at = null,
                        can_edit = 1L,
                        can_share = 1L,
                        approved_by = null,
                        approved_at = null,
                        deleted_at = null,
                        invited_by = null,
                        tagline = null,
                        avatar_type = "auto",
                        timezone = "UTC",
                    )
                    sql.usersQueries.insert(
                        id = "u2",
                        email = "babbage@example.com",
                        email_normalized = "babbage@example.com",
                        password_hash = "phc",
                        role = "MEMBER",
                        display_name = "Babbage",
                        status = "ACTIVE",
                        created_at = 1L,
                        updated_at = 1L,
                        last_login_at = null,
                        can_edit = 1L,
                        can_share = 1L,
                        approved_by = null,
                        approved_at = null,
                        deleted_at = null,
                        invited_by = null,
                        tagline = null,
                        avatar_type = "auto",
                        timezone = "UTC",
                    )
                }

                val repo = PublicProfileRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val maintainer = PublicProfileMaintainer(sql = sql, publicProfileRepo = repo)

                runTest {
                    maintainer.backfillAll()

                    val ids = repo.pullSince(userId = null, cursor = 0, limit = 50).items.map { it.id }
                    ids shouldContainExactlyInAnyOrder listOf("u1", "u2")
                }
            }
        }
    })
