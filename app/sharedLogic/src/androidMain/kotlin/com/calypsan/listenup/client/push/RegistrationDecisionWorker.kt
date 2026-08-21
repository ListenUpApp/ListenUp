package com.calypsan.listenup.client.push

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.AdminRepository
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Applies an Approve/Deny decision taken straight from a registration notification (#1068).
 *
 * Runs as work rather than inline in the [PushActionReceiver] because a broadcast receiver gets
 * roughly ten seconds and no network guarantees, while this is an authenticated RPC round-trip. The
 * decision the admin expressed must survive a flaky connection, a doze window, or the app being
 * killed a second after they tapped — dropping it silently would be worse than never offering the
 * button, because the admin has already been told the decision was made.
 *
 * Retries only on a genuinely retryable failure. A rejection the server means (already decided, no
 * longer permitted) is final: retrying it would loop forever against a server that has answered.
 */
class RegistrationDecisionWorker(
    context: Context,
    params: WorkerParameters,
    private val adminRepository: AdminRepository,
) : CoroutineWorker(context, params) {
    companion object {
        const val KEY_USER_ID = "registration_user_id"
        const val KEY_APPROVE = "registration_approve"

        /** Bounded so a permanently unreachable server stops rather than retrying forever. */
        private const val MAX_ATTEMPTS = 5
    }

    override suspend fun doWork(): Result {
        val userId = inputData.getString(KEY_USER_ID) ?: return Result.failure()
        val approve = inputData.getBoolean(KEY_APPROVE, false)

        // The approved user's info is discarded deliberately: the roster arrives by sync, so the
        // worker's only job is to make the decision stick.
        val outcome: AppResult<Any?> =
            if (approve) {
                adminRepository.approveUser(userId)
            } else {
                adminRepository.denyUser(userId)
            }

        return when (outcome) {
            is AppResult.Success -> {
                logger.info { "Registration decision applied from notification: approved=$approve" }
                Result.success()
            }

            is AppResult.Failure -> {
                val error = outcome.error
                // isRetryable is the server's own contract (see AppError): true only when the same
                // call can be blindly re-fired. Anything else is a decision the server has already
                // made, and re-sending it would spin.
                if (error.isRetryable && runAttemptCount < MAX_ATTEMPTS) {
                    logger.warn { "Registration decision failed, will retry: ${error.code}" }
                    Result.retry()
                } else {
                    // The request itself is untouched — it is still in the synced admin roster, so
                    // the admin can open the app and decide there. A lost tap costs a tap.
                    logger.warn { "Registration decision not applied: ${error.code}" }
                    Result.failure()
                }
            }
        }
    }
}
