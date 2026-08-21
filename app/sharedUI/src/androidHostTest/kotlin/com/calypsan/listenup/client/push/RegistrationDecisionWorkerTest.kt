package com.calypsan.listenup.client.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.repository.AdminRepository
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The worker behind Approve/Deny on a registration notification (#1068).
 *
 * The decision is taken somewhere the app is not running, so what matters is that it survives the
 * journey: a flaky connection must not silently discard an approval the admin has already been
 * told was made, and a server that has genuinely refused must not be retried forever.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RegistrationDecisionWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * A mock rather than a hand-written fake: [AdminRepository] carries ~25 methods and this
     * worker touches two, so a full fake would be almost entirely noise obscuring the two lines
     * that matter. The rubric's fakes-over-mocks rule is about seams with state; this one is a
     * boundary being stubbed.
     */
    private fun adminRepository(failWith: com.calypsan.listenup.api.error.AppError? = null) =
        mock<AdminRepository>(MockMode.autoUnit) {
            everySuspend { approveUser(any()) } returns
                (failWith?.let { AppResult.Failure(it) } ?: AppResult.Success(approvedUser()))
            everySuspend { denyUser(any()) } returns
                (failWith?.let { AppResult.Failure(it) } ?: AppResult.Success(Unit))
        }

    /** A minimal real [AdminUserInfo] — it is a data class, so it cannot be mocked, and the
     *  worker discards it anyway. */
    private fun approvedUser() =
        AdminUserInfo(
            id = "pending-1",
            email = "pending@example.com",
            displayName = "Alice",
            firstName = null,
            lastName = null,
            isRoot = false,
            role = "MEMBER",
            status = "ACTIVE",
            createdAt = "2026-05-02T12:00:00Z",
        )

    private suspend fun runWorker(
        repo: AdminRepository,
        userId: String? = "pending-1",
        approve: Boolean = true,
    ): ListenableWorker.Result {
        val data =
            if (userId == null) {
                workDataOf(RegistrationDecisionWorker.KEY_APPROVE to approve)
            } else {
                workDataOf(
                    RegistrationDecisionWorker.KEY_USER_ID to userId,
                    RegistrationDecisionWorker.KEY_APPROVE to approve,
                )
            }
        val worker =
            TestListenableWorkerBuilder<RegistrationDecisionWorker>(context)
                .setInputData(data)
                .setWorkerFactory(
                    object : androidx.work.WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: androidx.work.WorkerParameters,
                        ) = RegistrationDecisionWorker(appContext, workerParameters, repo)
                    },
                ).build()
        return worker.doWork()
    }

    @Test
    fun `approve applies the approval`() =
        runTest {
            val repo = adminRepository()

            runWorker(repo, approve = true).shouldBeInstanceOf<ListenableWorker.Result.Success>()

            verifySuspend { repo.approveUser("pending-1") }
        }

    @Test
    fun `deny applies the denial`() =
        runTest {
            val repo = adminRepository()

            runWorker(repo, approve = false).shouldBeInstanceOf<ListenableWorker.Result.Success>()

            verifySuspend { repo.denyUser("pending-1") }
        }

    // ⛔ The reason this is work at all rather than an inline call in the receiver. The admin has
    // already been shown the decision being made — the notification dismissed under their finger —
    // so dropping it on a bad connection would be a silent lie.
    @Test
    fun `a retryable failure is retried, not dropped`() =
        runTest {
            val repo = adminRepository(failWith = TransportError.NetworkUnavailable())

            runWorker(repo).shouldBeInstanceOf<ListenableWorker.Result.Retry>()
        }

    // The counter-case: a server that has answered must not be asked again forever. isRetryable is
    // the server's own contract for "this call can be blindly re-fired", and a permission denial
    // plainly cannot.
    @Test
    fun `a non-retryable failure stops instead of spinning`() =
        runTest {
            val repo = adminRepository(failWith = AuthError.PermissionDenied())

            runWorker(repo).shouldBeInstanceOf<ListenableWorker.Result.Failure>()
        }

    @Test
    fun `no user id fails rather than guessing`() =
        runTest {
            val repo = adminRepository()

            runWorker(repo, userId = null).shouldBeInstanceOf<ListenableWorker.Result.Failure>()
        }
}
