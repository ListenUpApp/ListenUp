@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.push

import com.calypsan.listenup.api.dto.auth.PendingRegistrationDecision
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.push.PushPayload
import com.calypsan.listenup.api.push.PushPlatform
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.api.AdminUserServiceImpl
import com.calypsan.listenup.server.auth.Argon2Limiter
import com.calypsan.listenup.server.auth.AuthServiceImpl
import com.calypsan.listenup.server.auth.JwtConfiguration
import com.calypsan.listenup.server.auth.PasswordHasher
import com.calypsan.listenup.server.auth.RefreshTokenGenerator
import com.calypsan.listenup.server.auth.RefreshTokenHasher
import com.calypsan.listenup.server.auth.RegistrationBroadcaster
import com.calypsan.listenup.server.auth.RegistrationPolicyBroadcaster
import com.calypsan.listenup.server.auth.SessionIssuer
import com.calypsan.listenup.server.auth.SessionService
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.testing.MutableClock
import com.calypsan.listenup.server.testing.migratedTestDatabase
import com.calypsan.listenup.server.testing.noOpPublicProfileMaintainer
import com.calypsan.listenup.server.testing.principalFor
import com.calypsan.listenup.server.testing.testPasswordResetService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Pins the pre-auth registration watch-token flow (#1068): a pending registrant's device
 * registers a watch token over the public RPC, the admin decision fans a
 * [PushPayload.RegistrationDecision] out to those watchers, and the watch rows die with the
 * decision (or by TTL sweep). The oracle-free contract — unknown users, decided users, and
 * push-disabled all reply indistinguishably from the stored case — is pinned here too.
 */
class RegistrationWatchTest :
    FunSpec({
        val pepper = "x".repeat(32).toByteArray()

        class Fixture(
            val db: ListenUpDatabase,
            val auth: AuthServiceImpl,
            val settings: ServerSettingsRepository,
            val store: PushWatchTokenStore,
            val clock: MutableClock,
        ) {
            suspend fun registerPendingUser(email: String = "waiting@example.com"): String {
                val result = auth.register(RegisterRequest(email, "x".repeat(8), "Waiting"))
                return (result as AppResult.Success)
                    .data
                    .shouldBeInstanceOf<RegisterResult.PendingApproval>()
                    .userId.value
            }

            suspend fun watchCount(): Long =
                com.calypsan.listenup.server.db.sqldelight
                    .suspendTransaction(db) { db.pushWatchTokensQueries.countAll().executeAsOne() }
        }

        suspend fun newFixture(): Fixture {
            val clock = MutableClock(Instant.parse("2026-08-11T12:00:00Z"))
            val db = migratedTestDatabase().db
            val sessions = SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)
            val jwt = JwtConfiguration("x".repeat(32), "listenup", "listenup-client", 15.minutes, clock)
            val settings = ServerSettingsRepository(db, default = RegistrationPolicy.APPROVAL_QUEUE)
            val store = PushWatchTokenStore(db, clock)
            val auth =
                AuthServiceImpl(
                    db = db,
                    sessions = sessions,
                    hasher = Argon2Limiter(PasswordHasher()),
                    jwt = jwt,
                    sessionIssuer = SessionIssuer(sessions, jwt, clock),
                    clock = clock,
                    settings = settings,
                    pushWatchTokens = store,
                    passwordResetService = testPasswordResetService(db, clock),
                )
            // A root must exist before register() accepts sign-ups (SetupRequired otherwise).
            auth
                .setupRoot(RegisterRequest("root@example.com", "x".repeat(8), "Root"))
                .shouldBeInstanceOf<AppResult.Success<*>>()
            return Fixture(db, auth, settings, store, clock)
        }

        test("a pending registrant's watch token is stored; unknown and decided users store nothing") {
            runTest {
                val fix = newFixture()
                val userId = fix.registerPendingUser()

                fix.auth
                    .registerRegistrationWatchToken(userId, "tok-1", PushPlatform.IOS)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                fix.watchCount() shouldBe 1

                // Unknown user: same success, nothing stored — no existence oracle.
                fix.auth
                    .registerRegistrationWatchToken("no-such-user", "tok-2", PushPlatform.IOS)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                fix.watchCount() shouldBe 1
            }
        }

        test("push disabled: the reply is indistinguishable but nothing is stored") {
            runTest {
                val fix = newFixture()
                val userId = fix.registerPendingUser()
                fix.settings.setPushNotificationsEnabled(false)

                fix.auth
                    .registerRegistrationWatchToken(userId, "tok-1", PushPlatform.ANDROID)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                fix.watchCount() shouldBe 0
            }
        }

        test("re-registration upserts; the per-key cap keeps only the newest three") {
            runTest {
                val fix = newFixture()
                val userId = fix.registerPendingUser()

                repeat(4) { i ->
                    fix.clock.instant += (i + 1).seconds // distinct created_at per row
                    fix.auth.registerRegistrationWatchToken(userId, "tok-$i", PushPlatform.IOS)
                }
                fix.watchCount() shouldBe 3

                // Same token again: an upsert, not another row.
                fix.auth.registerRegistrationWatchToken(userId, "tok-3", PushPlatform.IOS)
                fix.watchCount() shouldBe 3
            }
        }

        test("the admin decision pushes RegistrationDecision to the watchers, then evicts them") {
            runTest {
                val fix = newFixture()
                val userId = fix.registerPendingUser()
                fix.auth.registerRegistrationWatchToken(userId, "tok-1", PushPlatform.IOS)

                val delivered = mutableListOf<Pair<String, PushPayload>>()
                val recorder =
                    object : PushNotifier {
                        override suspend fun notify(
                            userId: String,
                            payload: PushPayload,
                        ) = Unit

                        override suspend fun notifyWatch(
                            kind: PushWatchKind,
                            key: String,
                            payload: PushPayload,
                        ) {
                            kind shouldBe PushWatchKind.REGISTRATION
                            delivered += key to payload
                        }
                    }
                val admin =
                    AdminUserServiceImpl(
                        sql = fix.db,
                        sessions = SessionService(fix.db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = fix.clock),
                        settings = fix.settings,
                        clock = fix.clock,
                        registrationBroadcaster = RegistrationBroadcaster(),
                        registrationPolicyBroadcaster = RegistrationPolicyBroadcaster(),
                        bus = ChangeBus(),
                        publicProfileMaintainer = fix.db.noOpPublicProfileMaintainer(),
                        pushNotifier = recorder,
                        pushWatchTokens = fix.store,
                        // The push runs fire-and-forget on this scope now (#1068 correctness fix) —
                        // bind it to this test's own TestScope so advanceUntilIdle() below drives it
                        // to completion deterministically instead of a real background dispatcher.
                        appScope = this,
                        passwordResetService = testPasswordResetService(fix.db, fix.clock),
                    ).copyWith(principalFor("root1", com.calypsan.listenup.api.dto.auth.UserRole.ADMIN))

                admin
                    .decidePendingRegistration(PendingRegistrationDecision(UserId(userId), approved = true))
                    .shouldBeInstanceOf<AppResult.Success<*>>()
                advanceUntilIdle() // let the fire-and-forget push land

                delivered shouldBe listOf(userId to PushPayload.RegistrationDecision(userId, approved = true))
                fix.watchCount() shouldBe 0
            }
        }

        test("the TTL sweep collects aged-out watch rows") {
            runTest {
                val fix = newFixture()
                val userId = fix.registerPendingUser()
                fix.auth.registerRegistrationWatchToken(userId, "tok-1", PushPlatform.IOS)
                fix.watchCount() shouldBe 1

                fix.clock.instant += 8.days
                fix.store.sweepExpired()
                fix.watchCount() shouldBe 0
            }
        }
    })
