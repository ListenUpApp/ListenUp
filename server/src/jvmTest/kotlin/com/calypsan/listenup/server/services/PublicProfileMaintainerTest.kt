package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest

class PublicProfileMaintainerTest :
    FunSpec({
        test("refresh builds a row from users + user_stats") {
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
                        avatar_type = "image",
                        timezone = "UTC",
                    )
                    sql.userStatsQueries.insert(
                        id = "u1",
                        user_id = "u1",
                        total_seconds_all_time = 5000L,
                        total_seconds_last_7_days = 700L,
                        total_seconds_last_30_days = 3000L,
                        books_started = 0L,
                        books_finished = 4L,
                        current_streak_days = 3L,
                        longest_streak_days = 9L,
                        last_event_date = null,
                        revision = 1L,
                        created_at = 1L,
                        updated_at = 1L,
                        deleted_at = null,
                        client_op_id = null,
                    )
                }

                val repo = PublicProfileRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val maintainer = PublicProfileMaintainer(sql = sql, publicProfileRepo = repo)

                runTest {
                    maintainer.refresh("u1")

                    val saved = repo.pullSince(userId = null, cursor = 0, limit = 10).items.single()
                    saved.id shouldBe "u1"
                    saved.displayName shouldBe "Ada"
                    saved.avatarType shouldBe "image"
                    saved.totalSecondsAllTime shouldBe 5000L
                    saved.booksFinished shouldBe 4
                    saved.longestStreakDays shouldBe 9
                    // No listening events seeded → window sum is 0
                    saved.totalSecondsLast365Days shouldBe 0L
                }
            }
        }

        test("refresh on a user with no user_stats row yields a zero-stats projection") {
            withSqlDatabase {
                sql.transaction {
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
                    maintainer.refresh("u2")

                    val saved = repo.pullSince(userId = null, cursor = 0, limit = 10).items.single()
                    saved.id shouldBe "u2"
                    saved.displayName shouldBe "Babbage"
                    saved.totalSecondsAllTime shouldBe 0L
                    saved.booksFinished shouldBe 0
                }
            }
        }

        test("refresh projects the user's tagline") {
            withSqlDatabase {
                sql.transaction {
                    sql.usersQueries.insert(
                        id = "u4",
                        email = "lovelace@example.com",
                        email_normalized = "lovelace@example.com",
                        password_hash = "phc",
                        role = "MEMBER",
                        display_name = "Ada Lovelace",
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
                        tagline = "Fantasy & sci-fi devotee",
                        avatar_type = "auto",
                        timezone = "UTC",
                    )
                }

                val repo = PublicProfileRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                val maintainer = PublicProfileMaintainer(sql = sql, publicProfileRepo = repo)

                runTest {
                    maintainer.refresh("u4")

                    val saved = repo.pullSince(userId = null, cursor = 0, limit = 10).items.single()
                    saved.id shouldBe "u4"
                    saved.tagline shouldBe "Fantasy & sci-fi devotee"
                }
            }
        }

        test("tombstone soft-deletes the projection row") {
            withSqlDatabase {
                sql.transaction {
                    sql.usersQueries.insert(
                        id = "u3",
                        email = "turing@example.com",
                        email_normalized = "turing@example.com",
                        password_hash = "phc",
                        role = "MEMBER",
                        display_name = "Turing",
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
                    maintainer.refresh("u3")
                    maintainer.tombstone("u3")

                    // pullSince returns all rows including soft-deleted ones
                    val saved = repo.pullSince(userId = null, cursor = 0, limit = 10).items.single()
                    saved.deletedAt shouldNotBe null
                    saved.deletedAt.shouldNotBeNull()
                }
            }
        }
    })
