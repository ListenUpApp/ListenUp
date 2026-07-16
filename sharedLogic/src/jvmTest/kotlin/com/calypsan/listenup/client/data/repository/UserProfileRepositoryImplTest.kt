package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.client.data.local.db.IdRevision
import com.calypsan.listenup.client.data.local.db.PublicProfileDao
import com.calypsan.listenup.client.data.local.db.PublicProfileEntity
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest

private fun publicProfile(
    id: String = "other-user",
    displayName: String = "Grace Hopper",
    avatarType: String = "auto",
    avatarUpdatedAt: Long = 0,
    deletedAt: Long? = null,
) = PublicProfileEntity(
    id = id,
    displayName = displayName,
    avatarType = avatarType,
    totalSecondsAllTime = 0,
    totalSecondsLast7Days = 0,
    totalSecondsLast30Days = 0,
    totalSecondsLast365Days = 0,
    booksFinished = 0,
    currentStreakDays = 0,
    longestStreakDays = 0,
    avatarUpdatedAt = avatarUpdatedAt,
    revision = 1,
    deletedAt = deletedAt,
)

/** In-memory fake: only the two reads [UserProfileRepositoryImpl] uses are backed. */
private class StubReadPublicProfileDao(
    private val rows: MutableStateFlow<Map<String, PublicProfileEntity>>,
) : PublicProfileDao {
    override fun observeById(userId: String): Flow<PublicProfileEntity?> = rows.map { it[userId]?.takeIf { row -> row.deletedAt == null } }

    override suspend fun findById(userId: String): PublicProfileEntity? = rows.value[userId]

    override fun observeAll(): Flow<List<PublicProfileEntity>> = error("unused")

    override suspend fun upsert(entity: PublicProfileEntity) = error("unused")

    override suspend fun softDelete(
        id: String,
        deletedAt: Long,
        revision: Long,
    ) = error("unused")

    override suspend fun digestRows(max: Long): List<IdRevision> = error("unused")

    override suspend fun revisionOf(id: String): Long? = error("unused")

    override suspend fun deleteAll() = error("unused")
}

class UserProfileRepositoryImplTest :
    FunSpec({
        fun repoWith(
            vararg entities: PublicProfileEntity,
        ): Pair<UserProfileRepositoryImpl, MutableStateFlow<Map<String, PublicProfileEntity>>> {
            val rows = MutableStateFlow(entities.associateBy { it.id })
            return UserProfileRepositoryImpl(StubReadPublicProfileDao(rows)) to rows
        }

        test("observeProfile emits another user's displayName and avatarType from public_profiles") {
            val (repo, _) = repoWith(publicProfile(id = "other", displayName = "Grace Hopper", avatarType = "image"))
            repo.observeProfile("other").test {
                val profile = awaitItem()
                profile shouldNotBe null
                profile!!.displayName shouldBe "Grace Hopper"
                profile.avatarType shouldBe "image"
                cancelAndIgnoreRemainingEvents()
            }
        }

        test("observeProfile maps avatarUpdatedAt onto updatedAt (the Coil cache-key version)") {
            val (repo, _) = repoWith(publicProfile(id = "other", avatarUpdatedAt = 4242L))
            repo.observeProfile("other").test {
                awaitItem()!!.updatedAt shouldBe 4242L
                cancelAndIgnoreRemainingEvents()
            }
        }

        test("observeProfile emits null for a tombstoned public profile") {
            val (repo, _) = repoWith(publicProfile(id = "gone", deletedAt = 99L))
            repo.observeProfile("gone").test {
                awaitItem().shouldBeNull()
                cancelAndIgnoreRemainingEvents()
            }
        }

        test("observeProfile re-emits with a new updatedAt when avatarUpdatedAt bumps") {
            val (repo, rows) = repoWith(publicProfile(id = "other", avatarUpdatedAt = 100L))
            repo.observeProfile("other").test {
                awaitItem()!!.updatedAt shouldBe 100L
                rows.value = mapOf("other" to publicProfile(id = "other", avatarUpdatedAt = 200L))
                awaitItem()!!.updatedAt shouldBe 200L
                cancelAndIgnoreRemainingEvents()
            }
        }

        test("getById returns the mapped profile for a live row") {
            val (repo, _) = repoWith(publicProfile(id = "other", displayName = "Ada Lovelace"))
            runTest {
                repo.getById("other")!!.displayName shouldBe "Ada Lovelace"
            }
        }

        test("getById returns null for a tombstoned row") {
            val (repo, _) = repoWith(publicProfile(id = "gone", deletedAt = 99L))
            runTest {
                repo.getById("gone").shouldBeNull()
            }
        }
    })
