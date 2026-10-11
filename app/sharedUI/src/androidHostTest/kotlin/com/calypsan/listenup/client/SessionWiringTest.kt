package com.calypsan.listenup.client

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.domain.model.AuthState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

/**
 * Tests for [SessionWiring] — wiring that depends on a live session (background sync, push
 * registration, recent-book shortcuts) must follow the session, not the process.
 *
 * The bug that motivates this: the wiring waited for the FIRST `Authenticated` state and stopped
 * looking. Signing out and back in without killing the app left the new session with no push token,
 * and after sign-out the periodic sync worker kept waking the device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionWiringTest :
    FunSpec({

        test("startup states then sign-in wires up once and never tears down") {
            runTest {
                val rig = Rig(this, AuthState.Initializing)

                rig.emit(AuthState.CheckingServer)
                rig.emit(authenticated())

                rig.signedIn shouldBe 1
                rig.signedOut shouldBe 0
                rig.stop()
            }
        }

        test("signing out and back in wires up again for the new session") {
            runTest {
                val rig = Rig(this, authenticated())

                rig.emit(AuthState.NeedsLogin())
                rig.emit(authenticated())

                rig.signedIn shouldBe 2
                rig.signedOut shouldBe 1
                rig.stop()
            }
        }

        test("a lapsed session is not a sign-out, and re-authenticating wires up again") {
            runTest {
                val rig = Rig(this, authenticated())

                rig.emit(AuthState.SessionLapsed(UserId("u1")))
                rig.emit(authenticated())

                rig.signedIn shouldBe 2
                rig.signedOut shouldBe 0
                rig.stop()
            }
        }

        test("the same authenticated state emitted twice wires up once") {
            runTest {
                val states = MutableSharedFlow<AuthState>()
                val rig = Rig(this, states)

                rig.emit(authenticated())
                rig.emit(authenticated())

                rig.signedIn shouldBe 1
                rig.stop()
            }
        }

        test("never signed in this process still cancels what an earlier process left scheduled") {
            runTest {
                val rig = Rig(this, AuthState.Initializing)

                rig.emit(AuthState.NeedsLogin())

                rig.signedIn shouldBe 0
                rig.signedOut shouldBe 1
                rig.stop()
            }
        }

        test("a sign-in step that throws does not end collection") {
            runTest {
                val rig = Rig(this, authenticated(), signInThrows = true)

                rig.emit(AuthState.NeedsLogin())

                rig.signedOut shouldBe 1
                rig.stop()
            }
        }
    })

private fun authenticated() = AuthState.Authenticated(userId = UserId("u1"), sessionId = SessionId("s1"))

/** Runs a [SessionWiring] over a controllable auth-state flow and counts what it fires. */
@OptIn(ExperimentalCoroutinesApi::class)
private class Rig private constructor(
    private val testScope: TestScope,
    states: Flow<AuthState>,
    private val emitter: suspend (AuthState) -> Unit,
    signInThrows: Boolean,
) {
    var signedIn = 0
        private set
    var signedOut = 0
        private set

    private val job: Job =
        CoroutineScope(testScope.coroutineContext).launch {
            SessionWiring(
                authStates = states,
                onSignedIn = {
                    yield()
                    signedIn++
                    if (signInThrows) error("push registration failed")
                },
                onSignedOut = {
                    yield()
                    signedOut++
                },
            ).run()
        }

    init {
        testScope.runCurrent()
    }

    suspend fun emit(state: AuthState) {
        emitter(state)
        testScope.runCurrent()
    }

    fun stop() = job.cancel()

    companion object {
        operator fun invoke(
            scope: TestScope,
            initial: AuthState,
            signInThrows: Boolean = false,
        ): Rig {
            val flow = MutableStateFlow(initial)
            return Rig(scope, flow, { flow.value = it }, signInThrows)
        }

        operator fun invoke(
            scope: TestScope,
            states: MutableSharedFlow<AuthState>,
        ): Rig = Rig(scope, states, { states.emit(it) }, signInThrows = false)
    }
}
