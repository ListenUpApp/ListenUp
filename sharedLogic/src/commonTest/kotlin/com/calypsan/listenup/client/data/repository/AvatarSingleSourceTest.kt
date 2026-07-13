package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.api.ProfileService
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.profile.Profile
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.IdRevision
import com.calypsan.listenup.client.data.local.db.PublicProfileDao
import com.calypsan.listenup.client.data.local.db.PublicProfileEntity
import com.calypsan.listenup.client.data.local.db.UserDao
import com.calypsan.listenup.client.data.local.db.UserEntity
import com.calypsan.listenup.client.data.local.db.PendingOperationV2Dao
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.core.Timestamp
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest

/**
 * The single-source guard for avatar state (closes the 6-bug class).
 *
 * The invariant: the field that CHANGES on an avatar write ([PublicProfileEntity.avatarUpdatedAt] /
 * [PublicProfileEntity.avatarType]) is the field the display OBSERVES ([UserProfileRepository.observeProfile]).
 * These tests prove [ProfileEditRepositoryImpl] writes the caller's OWN `public_profiles` row so the
 * observed avatar re-emits BEFORE any sync round-trip, and that the eventual SSE echo converges to
 * server truth without regressing the avatar version.
 */
class AvatarSingleSourceTest :
    FunSpec({
        val selfId = "self-1"
        val selfEntity =
            UserEntity(
                id = UserId(selfId),
                email = "self@example.com",
                displayName = "Self User",
                isRoot = false,
                createdAt = Timestamp(0L),
                updatedAt = Timestamp(0L),
            )

        fun publicRow(
            id: String = selfId,
            displayName: String = "Self User",
            avatarType: String = "auto",
            avatarUpdatedAt: Long = 0,
            revision: Long = 1,
            booksFinished: Int = 0,
            deletedAt: Long? = null,
        ) = PublicProfileEntity(
            id = id,
            displayName = displayName,
            avatarType = avatarType,
            totalSecondsAllTime = 0,
            totalSecondsLast7Days = 0,
            totalSecondsLast30Days = 0,
            totalSecondsLast365Days = 0,
            booksFinished = booksFinished,
            currentStreakDays = 0,
            longestStreakDays = 0,
            avatarUpdatedAt = avatarUpdatedAt,
            revision = revision,
            deletedAt = deletedAt,
        )

        fun repoWith(
            dao: PublicProfileDao,
            uploadResult: AppResult<Long> = AppResult.Success(0L),
            service: ProfileService = mock(),
            imageStorage: ImageStorage =
                mock {
                    everySuspend { saveUserAvatar(any(), any()) } returns AppResult.Success(Unit)
                    everySuspend { deleteUserAvatar(any()) } returns AppResult.Success(Unit)
                },
        ): ProfileEditRepositoryImpl {
            val userDao = mock<UserDao> { everySuspend { getCurrentUser() } returns selfEntity }
            return ProfileEditRepositoryImpl(
                userDao = userDao,
                publicProfileDao = dao,
                channel = RpcChannel.forTest(service),
                avatarUploader = { _, _ -> uploadResult },
                imageStorage = imageStorage,
                offlineEditor = unusedOfflineEditor(),
            )
        }

        test("uploadAvatar makes observeProfile emit image + the advanced avatarUpdatedAt before any sync") {
            runTest {
                val dao = RecordingPublicProfileDao()
                val serverTs = 5_000L
                val repo = repoWith(dao, uploadResult = AppResult.Success(serverTs))

                repo.uploadAvatar(byteArrayOf(1, 2, 3), "image/png").shouldBeInstanceOf<AppResult.Success<Unit>>()

                UserProfileRepositoryImpl(dao).observeProfile(selfId).test {
                    val profile = awaitItem()
                    profile!!.avatarType shouldBe "image"
                    profile.updatedAt shouldBe serverTs
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("revertToAutoAvatar makes observeProfile emit auto + the server version before any sync") {
            runTest {
                val dao = RecordingPublicProfileDao(MutableStateFlow(mapOf(selfId to publicRow(avatarType = "image", avatarUpdatedAt = 1_000L))))
                val serverTs = 9_000L
                val service =
                    mock<ProfileService> {
                        everySuspend { updateMyProfile(any()) } returns
                            WireAppResult.Success(Profile(UserId(selfId), "Self User", null, "auto", serverTs))
                    }
                val repo = repoWith(dao, service = service)

                repo.revertToAutoAvatar().shouldBeInstanceOf<AppResult.Success<Unit>>()

                UserProfileRepositoryImpl(dao).observeProfile(selfId).test {
                    val profile = awaitItem()
                    profile!!.avatarType shouldBe "auto"
                    profile.updatedAt shouldBe serverTs
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("an SSE echo after the optimistic upload converges to server truth without regressing the version") {
            runTest {
                // A pre-existing synced row: auto avatar, version 0, revision 1.
                val dao = RecordingPublicProfileDao(MutableStateFlow(mapOf(selfId to publicRow(avatarType = "auto", avatarUpdatedAt = 0L, revision = 1))))
                val serverTs = 5_000L
                val repo = repoWith(dao, uploadResult = AppResult.Success(serverTs))

                repo.uploadAvatar(byteArrayOf(1, 2, 3), "image/png").shouldBeInstanceOf<AppResult.Success<Unit>>()

                // The server rebuilt the projection and the SSE echo lands (higher revision, richer
                // stats, SAME avatar version the client already wrote).
                dao.upsert(publicRow(avatarType = "image", avatarUpdatedAt = serverTs, revision = 7, booksFinished = 3))

                UserProfileRepositoryImpl(dao).observeProfile(selfId).test {
                    val profile = awaitItem()!!
                    profile.avatarType shouldBe "image"
                    // Converged to server truth, never regressed to the pre-upload version (0).
                    profile.updatedAt shouldBe serverTs
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })

/**
 * A real [OfflineEditor] over mocked dependencies. The avatar paths under test never touch it —
 * it exists only to satisfy the constructor (OfflineEditor is final and can't be mocked directly).
 */
private fun unusedOfflineEditor(): OfflineEditor {
    val pendingDao = mock<PendingOperationV2Dao> { everySuspend { insert(any()) } returns Unit }
    val queue = PendingOperationQueue(dao = pendingDao, sender = PendingOperationSender { AppResult.Success(Unit) })
    val txRunner =
        object : TransactionRunner {
            override suspend fun <R> atomically(block: suspend () -> R): R = block()
        }
    return OfflineEditor(pendingQueue = queue, transactionRunner = txRunner, authSession = FakeAuthSession(userId = "self-1"))
}

/** In-memory reactive [PublicProfileDao] fake — backs the reads + upsert the avatar single-source path uses. */
private class RecordingPublicProfileDao(
    private val rows: MutableStateFlow<Map<String, PublicProfileEntity>> = MutableStateFlow(emptyMap()),
) : PublicProfileDao {
    override fun observeById(userId: String): Flow<PublicProfileEntity?> = rows.map { it[userId]?.takeIf { row -> row.deletedAt == null } }

    override suspend fun findById(userId: String): PublicProfileEntity? = rows.value[userId]

    override suspend fun upsert(entity: PublicProfileEntity) {
        rows.value = rows.value + (entity.id to entity)
    }

    override fun observeAll(): Flow<List<PublicProfileEntity>> = error("unused")

    override suspend fun softDelete(
        id: String,
        deletedAt: Long,
        revision: Long,
    ) = error("unused")

    override suspend fun digestRows(max: Long): List<IdRevision> = error("unused")

    override suspend fun revisionOf(id: String): Long? = rows.value[id]?.revision
}
