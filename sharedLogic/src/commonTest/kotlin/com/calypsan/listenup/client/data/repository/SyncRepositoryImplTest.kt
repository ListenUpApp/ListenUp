package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Regression coverage for the post-import resync lifecycle ([refreshListeningHistoryDetached]).
 *
 * `refreshListeningHistory` is fired from the ABS import wizard's `viewModelScope` the moment the
 * wizard shows "Done" — and the imported playback positions reach Room only via the catch-up it
 * drives (the import writes them firehose-suppressed, so there is no live SSE push). The wizard is
 * dismissable the instant "Done" appears, so running the catch-up in the caller's context let a
 * dismissal cancel it mid-drain: the imported history never landed (and the firehose was left
 * disconnected) until a later, app-scoped sync trigger. The catch-up must run on the app-scoped sync
 * scope and complete regardless of the caller's lifecycle.
 */
class SyncRepositoryImplTest :
    FunSpec({
        test("post-import catch-up completes even when the calling scope is cancelled mid-drain") {
            runTest {
                val recoveryStarted = CompletableDeferred<Unit>()
                val releaseRecovery = CompletableDeferred<Unit>()
                var recoveryCompleted = false

                // The app-scoped sync scope: long-lived, independent of any one screen.
                val appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

                // Stand in for the import wizard's viewModelScope: a caller we cancel mid-catch-up.
                val caller =
                    launch(UnconfinedTestDispatcher(testScheduler)) {
                        refreshListeningHistoryDetached(
                            scope = appScope,
                            ensureStarted = { AppResult.Success(Unit) },
                            recover = {
                                recoveryStarted.complete(Unit)
                                releaseRecovery.await()
                                recoveryCompleted = true
                            },
                        )
                    }

                recoveryStarted.await()
                caller.cancel() // wizard dismissed while the catch-up is still draining
                releaseRecovery.complete(Unit)
                advanceUntilIdle()

                recoveryCompleted shouldBe true

                appScope.cancel()
            }
        }

        test("post-import catch-up is skipped, and the failure surfaced, when the engine won't start") {
            runTest {
                var recovered = false

                val result =
                    refreshListeningHistoryDetached(
                        scope = backgroundScope,
                        ensureStarted = { AppResult.Failure(InternalError()) },
                        recover = { recovered = true },
                    )

                result.shouldBeInstanceOf<AppResult.Failure>()
                recovered shouldBe false
            }
        }
    })
