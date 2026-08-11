package com.calypsan.listenup.client.automotive

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.domain.model.AuthState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * Pins the signed-out → signed-in edge that refreshes the Auto browse tree (#1245).
 *
 * Pure Flow logic with no Android surface, so this is a plain Kotest [FunSpec] rather than the
 * Robolectric-runner shape [AutoBrowseErrorsTest] needs for its `PendingIntent` assertions. The
 * flows under test are finite, so `toList()` says everything Turbine would and says it shorter.
 *
 * The risk being pinned is over-firing as much as under-firing: `notifyChildrenChanged` tells a
 * head unit to throw away what it has and ask again, so an edge that fired on every auth emission
 * would churn the browse tree of a car that is doing nothing wrong.
 */
class BrowseSignInEdgesTest :
    FunSpec({

        val user = UserId("u1")
        val signedOut = AuthState.NeedsLogin(openRegistration = false)

        fun authenticated(session: String) = AuthState.Authenticated(user, SessionId(session))

        suspend fun edgesOf(vararg states: AuthState): List<Unit> = flowOf(*states).browseSignInEdges().toList()

        test("fires once when a signed-out session becomes authenticated") {
            runTest {
                edgesOf(signedOut, authenticated("s1")) shouldBe listOf(Unit)
            }
        }

        test("stays silent for a service that starts up already authenticated") {
            // Nothing was ever walled off, so there is nothing to refresh — the current state is
            // state, not a transition.
            runTest {
                edgesOf(authenticated("s1")).shouldBeEmpty()
            }
        }

        test("stays silent across the startup states that never gate browse") {
            // Initializing/CheckingServer resolve into Authenticated without ever showing a wall.
            // Treating that as an edge would refresh the tree on every cold start.
            runTest {
                edgesOf(AuthState.Initializing, AuthState.CheckingServer, authenticated("s1"))
                    .shouldBeEmpty()
            }
        }

        test("stays silent when a session lapses and recovers") {
            // SessionLapsed deliberately does not gate browse (never stranded — Room still serves
            // the library offline), so neither leg of that round trip is an edge.
            runTest {
                edgesOf(authenticated("s1"), AuthState.SessionLapsed(user), authenticated("s2"))
                    .shouldBeEmpty()
            }
        }

        test("does not fire on the signed-in to signed-out leg") {
            runTest {
                edgesOf(authenticated("s1"), signedOut).shouldBeEmpty()
            }
        }

        test("fires again on a second sign-out and sign-in") {
            runTest {
                edgesOf(signedOut, authenticated("s1"), signedOut, authenticated("s2")) shouldBe
                    listOf(Unit, Unit)
            }
        }
    })
