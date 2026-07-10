@file:OptIn(ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.data.connection

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class ConnectionIssueReporterTest :
    FunSpec({
        test("a burst of session-invalidating reports emits exactly once per lapse") {
            runTest {
                val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
                try {
                    val errorBus = ErrorBus()
                    val session = FakeAuthSession(authState = AuthState.Authenticated(UserId("u1"), SessionId("s1")))
                    val reporter = ConnectionIssueReporter(errorBus, session, scope)

                    errorBus.errors.test {
                        // 19-domains-in-one-pass shape: many reports, one emission.
                        reporter.report(AuthError.SessionExpired(debugInfo = "digest books"))
                        awaitItem().shouldBeInstanceOf<AuthError.SessionExpired>()
                        reporter.report(AuthError.SessionExpired(debugInfo = "digest series"))
                        reporter.report(AuthError.InvalidRefreshToken(familyRevoked = false))
                        expectNoEvents()
                    }
                } finally {
                    scope.cancel()
                }
            }
        }

        test("re-authentication re-arms the reporter for the NEXT lapse") {
            runTest {
                val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
                try {
                    val errorBus = ErrorBus()
                    val session = FakeAuthSession(authState = AuthState.Authenticated(UserId("u1"), SessionId("s1")))
                    val reporter = ConnectionIssueReporter(errorBus, session, scope)

                    errorBus.errors.test {
                        reporter.report(AuthError.SessionExpired())
                        awaitItem().shouldBeInstanceOf<AuthError.SessionExpired>()

                        // Observer would flip to SessionLapsed; then login restores Authenticated.
                        session.authState.value = AuthState.SessionLapsed(UserId("u1"))
                        session.authState.value = AuthState.Authenticated(UserId("u1"), SessionId("s2"))

                        reporter.report(AuthError.SessionExpired())
                        awaitItem().shouldBeInstanceOf<AuthError.SessionExpired>()
                    }
                } finally {
                    scope.cancel()
                }
            }
        }

        test("non-session errors and reports while not Authenticated are swallowed") {
            runTest {
                val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
                try {
                    val errorBus = ErrorBus()
                    val session = FakeAuthSession(authState = AuthState.SessionLapsed(UserId("u1")))
                    val reporter = ConnectionIssueReporter(errorBus, session, scope)

                    errorBus.errors.test {
                        reporter.report(TransportError.Server5xx(statusCode = 500)) // not session-invalidating
                        reporter.report(AuthError.SessionExpired()) // already lapsed — no re-report
                        expectNoEvents()
                    }
                } finally {
                    scope.cancel()
                }
            }
        }
    })
