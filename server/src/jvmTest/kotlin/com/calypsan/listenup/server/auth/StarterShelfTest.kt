@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.ShelfRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.migratedTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Verifies the starter-shelf seam: registering a user (via [AuthServiceImpl.register])
 * auto-creates a "To Read" shelf. Failure of the shelf creation must NOT fail registration.
 *
 * Also checks [AuthServiceImpl.setupRoot] — the ROOT user is created via a separate code
 * path but deserves a shelf too.
 */
class StarterShelfTest :
    FunSpec({
        val pepper = "x".repeat(32).toByteArray()
        val clock = FixedClock(Instant.parse("2026-06-04T12:00:00Z"))

        fun newComponents(): Pair<AuthServiceImpl, ShelfRepository> {
            val db = migratedTestDatabase().db
            val hasher = PasswordHasher()
            val sessions =
                SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)
            val jwt = JwtConfiguration("x".repeat(32), "listenup", "listenup-client", 15.minutes, clock)
            val settings = ServerSettingsRepository(db, default = RegistrationPolicy.OPEN)
            val shelfRepo = ShelfRepository(db, ChangeBus(), SyncRegistry(), clock)
            val authSvc =
                AuthServiceImpl(
                    db = db,
                    sessions = sessions,
                    hasher = Argon2Limiter(hasher),
                    jwt = jwt,
                    sessionIssuer = SessionIssuer(sessions, jwt, clock),
                    clock = clock,
                    settings = settings,
                    shelfRepository = shelfRepo,
                )
            return Pair(authSvc, shelfRepo)
        }

        test("register creates a To Read shelf for the new member") {
            val (authSvc, shelfRepo) = newComponents()
            runTest {
                // First user must be the root (setup), then we register a normal user.
                val rootResult = authSvc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root"))
                rootResult.shouldBeInstanceOf<AppResult.Success<*>>()
                val rootSession = (rootResult as AppResult.Success).data

                val memberResult = authSvc.register(RegisterRequest("alice@x", "x".repeat(8), "Alice"))
                val authed =
                    memberResult
                        .shouldBeInstanceOf<AppResult.Success<*>>()
                        .data
                        .shouldBeInstanceOf<RegisterResult.Authenticated>()
                val memberId = authed.session.user.id.value

                val shelves = shelfRepo.listOwnedBy(memberId)
                shelves shouldHaveSize 1
                shelves.first().name shouldBe "To Read"
            }
        }

        test("setupRoot creates a To Read shelf for the root user") {
            val (authSvc, shelfRepo) = newComponents()
            runTest {
                val rootResult = authSvc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root"))
                val rootSession = (rootResult as AppResult.Success).data
                val rootId = rootSession.user.id.value

                val shelves = shelfRepo.listOwnedBy(rootId)
                shelves shouldHaveSize 1
                shelves.first().name shouldBe "To Read"
            }
        }

        test("register with APPROVAL_QUEUE policy creates a To Read shelf for the pending member") {
            val db = migratedTestDatabase().db
            val hasher = PasswordHasher()
            val sessions =
                SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)
            val jwt = JwtConfiguration("x".repeat(32), "listenup", "listenup-client", 15.minutes, clock)
            val settings = ServerSettingsRepository(db, default = RegistrationPolicy.APPROVAL_QUEUE)
            val shelfRepo = ShelfRepository(db, ChangeBus(), SyncRegistry(), clock)
            val authSvc =
                AuthServiceImpl(
                    db = db,
                    sessions = sessions,
                    hasher = Argon2Limiter(hasher),
                    jwt = jwt,
                    sessionIssuer = SessionIssuer(sessions, jwt, clock),
                    clock = clock,
                    settings = settings,
                    shelfRepository = shelfRepo,
                )
            runTest {
                authSvc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldBeInstanceOf<AppResult.Success<*>>()
                val pendResult = authSvc.register(RegisterRequest("bob@x", "x".repeat(8), "Bob"))
                val pending = (pendResult as AppResult.Success).data.shouldBeInstanceOf<RegisterResult.PendingApproval>()

                val shelves = shelfRepo.listOwnedBy(pending.userId.value)
                shelves shouldHaveSize 1
                shelves.first().name shouldBe "To Read"
            }
        }

        test("starter shelf failure does NOT fail registration") {
            // Construct AuthServiceImpl WITHOUT a shelfRepository — shelf creation is null-safe.
            val db = migratedTestDatabase().db
            val hasher = PasswordHasher()
            val sessions =
                SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)
            val jwt = JwtConfiguration("x".repeat(32), "listenup", "listenup-client", 15.minutes, clock)
            val settings = ServerSettingsRepository(db, default = RegistrationPolicy.OPEN)
            val authSvcNoShelf =
                AuthServiceImpl(
                    db = db,
                    sessions = sessions,
                    hasher = Argon2Limiter(hasher),
                    jwt = jwt,
                    sessionIssuer = SessionIssuer(sessions, jwt, clock),
                    clock = clock,
                    settings = settings,
                    shelfRepository = null, // no shelf repo — must not fail registration
                )
            runTest {
                authSvcNoShelf
                    .setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root"))
                    .shouldBeInstanceOf<AppResult.Success<*>>()
                // Register still succeeds even with no shelf repo.
                val result = authSvcNoShelf.register(RegisterRequest("charlie@x", "x".repeat(8), "Charlie"))
                result.shouldBeInstanceOf<AppResult.Success<*>>()
            }
        }
    })
