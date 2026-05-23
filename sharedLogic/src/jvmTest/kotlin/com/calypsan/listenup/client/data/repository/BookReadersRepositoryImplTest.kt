package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.data.local.db.ActiveSessionEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.UserProfileEntity
import com.calypsan.listenup.client.data.local.db.PlaybackPositionEntity
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Integration tests for [BookReadersRepositoryImpl].
 *
 * Uses an in-memory Room database so both [ActiveSessionDao] and [PlaybackPositionDao]
 * are real implementations — this catches any SQL issues in [ActiveSessionDao.observeForBook]
 * and [PlaybackPositionDao.observeFinishedForBook].
 *
 * A minimal [AuthSession] fake provides the current user's id without pulling in
 * the full auth machinery.
 */
class BookReadersRepositoryImplTest :
    FunSpec({

        // ── Helpers ───────────────────────────────────────────────────────────

        fun db(): ListenUpDatabase = createInMemoryTestDatabase()

        fun session(
            sessionId: String,
            userId: String,
            bookId: String,
            startedAt: Long = 1_000L,
        ): ActiveSessionEntity =
            ActiveSessionEntity(
                sessionId = sessionId,
                userId = userId,
                bookId = bookId,
                startedAt = startedAt,
                updatedAt = startedAt,
            )

        fun profile(
            id: String,
            displayName: String,
        ): UserProfileEntity =
            UserProfileEntity(
                id = id,
                displayName = displayName,
                updatedAt = 0L,
            )

        fun finishedPosition(bookId: String): PlaybackPositionEntity =
            PlaybackPositionEntity(
                bookId = BookId(bookId),
                positionMs = 99_000L,
                playbackSpeed = 1.0f,
                updatedAt = 2_000L,
                lastPlayedAt = 2_000L,
                isFinished = true,
                deletedAt = null,
            )

        fun fakeAuthSession(userId: String?) =
            object : com.calypsan.listenup.client.domain.repository.AuthSession {
                override val authState =
                    kotlinx.coroutines.flow.MutableStateFlow(
                        com.calypsan.listenup.client.domain.model.AuthState.Initializing,
                    )

                override suspend fun saveAuthTokens(
                    access: com.calypsan.listenup.api.dto.auth.AccessToken,
                    refresh: com.calypsan.listenup.api.dto.auth.RefreshToken,
                    sessionId: String,
                    userId: String,
                ) = Unit

                override suspend fun getAccessToken(): com.calypsan.listenup.api.dto.auth.AccessToken? = null

                override suspend fun getRefreshToken(): com.calypsan.listenup.api.dto.auth.RefreshToken? = null

                override suspend fun getSessionId(): String? = null

                override suspend fun getUserId(): String? = userId

                override suspend fun updateAccessToken(token: com.calypsan.listenup.api.dto.auth.AccessToken) = Unit

                override suspend fun clearAuthTokens() = Unit

                override suspend fun isAuthenticated(): Boolean = userId != null

                override suspend fun initializeAuthState() = Unit

                override suspend fun checkServerStatus(): com.calypsan.listenup.client.domain.model.AuthState = com.calypsan.listenup.client.domain.model.AuthState.Initializing

                override suspend fun refreshOpenRegistration() = Unit

                override suspend fun savePendingRegistration(
                    userId: String,
                    email: String,
                ) = Unit

                override suspend fun getPendingRegistration(): com.calypsan.listenup.client.domain.repository.PendingRegistration? = null

                override suspend fun clearPendingRegistration() = Unit
            }

        // ── Active sessions appear in currentlyListening ──────────────────────

        test("active sessions for other users appear in currentlyListening") {
            runTest {
                val database = db()
                val repo =
                    BookReadersRepositoryImpl(
                        activeSessionDao = database.activeSessionDao(),
                        playbackPositionDao = database.playbackPositionDao(),
                        authSession = fakeAuthSession("u1"),
                    )

                database.userProfileDao().upsert(profile("u2", "Bob"))
                database.userProfileDao().upsert(profile("u3", "Carol"))
                database.activeSessionDao().upsertAll(
                    listOf(
                        session("s2", userId = "u2", bookId = "bookA"),
                        session("s3", userId = "u3", bookId = "bookA"),
                    ),
                )

                repo.observeReadersFor("bookA").test {
                    val readers = awaitItem()
                    readers.currentlyListening shouldHaveSize 2
                    readers.currentlyListening.map { it.userId } shouldContainExactly listOf("u2", "u3")
                    readers.currentlyListening.map { it.displayName } shouldContainExactly listOf("Bob", "Carol")
                    readers.completedBy.shouldBeEmpty()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ── Current user's finished position appears in completedBy ───────────

        test("current user appears in completedBy when their position is finished") {
            runTest {
                val database = db()
                val repo =
                    BookReadersRepositoryImpl(
                        activeSessionDao = database.activeSessionDao(),
                        playbackPositionDao = database.playbackPositionDao(),
                        authSession = fakeAuthSession("u1"),
                    )

                database.playbackPositionDao().save(finishedPosition("bookA"))

                repo.observeReadersFor("bookA").test {
                    val readers = awaitItem()
                    readers.completedBy shouldHaveSize 1
                    readers.completedBy[0].userId shouldBe "u1"
                    readers.completedBy[0].displayName shouldBe "You"
                    readers.totalCompletions shouldBe 1
                    readers.currentlyListening.shouldBeEmpty()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ── Current user not in currentlyListening ────────────────────────────

        test("current user's own active session does not appear in currentlyListening") {
            runTest {
                val database = db()
                val repo =
                    BookReadersRepositoryImpl(
                        activeSessionDao = database.activeSessionDao(),
                        playbackPositionDao = database.playbackPositionDao(),
                        authSession = fakeAuthSession("u1"),
                    )

                database.userProfileDao().upsert(profile("u1", "Me"))
                database.userProfileDao().upsert(profile("u2", "Bob"))
                database.activeSessionDao().upsertAll(
                    listOf(
                        // current user's session — must be filtered out
                        session("s1", userId = "u1", bookId = "bookA"),
                        // other user's session — must appear
                        session("s2", userId = "u2", bookId = "bookA"),
                    ),
                )

                repo.observeReadersFor("bookA").test {
                    val readers = awaitItem()
                    readers.currentlyListening shouldHaveSize 1
                    readers.currentlyListening[0].userId shouldBe "u2"
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ── Empty state when no sessions and no finished position ─────────────

        test("emits empty BookReaders when no sessions and no finished position") {
            runTest {
                val database = db()
                val repo =
                    BookReadersRepositoryImpl(
                        activeSessionDao = database.activeSessionDao(),
                        playbackPositionDao = database.playbackPositionDao(),
                        authSession = fakeAuthSession("u1"),
                    )

                repo.observeReadersFor("bookA").test {
                    val readers = awaitItem()
                    readers.currentlyListening.shouldBeEmpty()
                    readers.completedBy.shouldBeEmpty()
                    readers.totalCompletions shouldBe 0
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ── Sessions scoped to the queried bookId ─────────────────────────────

        test("sessions for a different book are not included") {
            runTest {
                val database = db()
                val repo =
                    BookReadersRepositoryImpl(
                        activeSessionDao = database.activeSessionDao(),
                        playbackPositionDao = database.playbackPositionDao(),
                        authSession = fakeAuthSession("u1"),
                    )

                database.userProfileDao().upsert(profile("u2", "Bob"))
                database.activeSessionDao().upsert(
                    session("s2", userId = "u2", bookId = "bookB"),
                )

                repo.observeReadersFor("bookA").test {
                    val readers = awaitItem()
                    readers.currentlyListening.shouldBeEmpty()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ── Profile-missing fallback ──────────────────────────────────────────

        test("user with no user_profiles row appears with displayName User") {
            runTest {
                val database = db()
                val repo =
                    BookReadersRepositoryImpl(
                        activeSessionDao = database.activeSessionDao(),
                        playbackPositionDao = database.playbackPositionDao(),
                        authSession = fakeAuthSession("u1"),
                    )

                // Seed session but NO corresponding profile row
                database.activeSessionDao().upsert(
                    session("s2", userId = "u2", bookId = "bookA"),
                )

                repo.observeReadersFor("bookA").test {
                    val readers = awaitItem()
                    readers.currentlyListening shouldHaveSize 1
                    readers.currentlyListening[0].displayName shouldBe "User"
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ── No debounce: emits on every change ────────────────────────────────

        test("emits immediately when a session is added without artificial delay") {
            runTest {
                val database = db()
                val repo =
                    BookReadersRepositoryImpl(
                        activeSessionDao = database.activeSessionDao(),
                        playbackPositionDao = database.playbackPositionDao(),
                        authSession = fakeAuthSession("u1"),
                    )

                database.userProfileDao().upsert(profile("u2", "Bob"))

                repo.observeReadersFor("bookA").test {
                    // First emission: empty
                    val first = awaitItem()
                    first.currentlyListening.shouldBeEmpty()

                    // Add a session → immediate new emission, no artificial delay
                    database.activeSessionDao().upsert(session("s2", userId = "u2", bookId = "bookA"))
                    val second = awaitItem()
                    second.currentlyListening shouldHaveSize 1
                    second.currentlyListening[0].userId shouldBe "u2"

                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })
