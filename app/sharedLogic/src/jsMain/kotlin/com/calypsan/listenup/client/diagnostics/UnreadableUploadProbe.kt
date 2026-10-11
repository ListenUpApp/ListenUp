package com.calypsan.listenup.client.diagnostics

import com.calypsan.listenup.client.data.settings.seedServerUrlFromOrigin
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.UploadCandidate
import com.calypsan.listenup.client.domain.repository.UploadRepository
import com.calypsan.listenup.client.domain.repository.UploadStep
import com.calypsan.listenup.client.presentation.auth.LoginViewModel
import com.calypsan.listenup.client.presentation.auth.SetupViewModel
import com.calypsan.listenup.core.FileSource
import com.calypsan.listenup.core.ServerUrl
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.w3c.dom.Worker
import kotlin.time.Duration.Companion.seconds

/**
 * How an upload of a file that cannot be read ended, as the real client stack reported it.
 *
 * Plain values, for the same reason [LibrarySyncProbe] uses them: the Koin graph, the upload
 * repository and its HTTP stack all stay `internal` to this module.
 */
data class UnreadableUploadProbe(
    /** True once `authState` reached [AuthState.Authenticated]. */
    val reachedAuthenticated: Boolean,
    /** The `code` of the error the upload failed with; null if it did not fail. */
    val errorCode: String?,
    /** Whether that error invites a retry; null if the upload did not fail. */
    val isRetryable: Boolean?,
    /** The user-facing text of that error; null if the upload did not fail. */
    val message: String?,
    /** Message from whatever went wrong in the probe itself, or null when the upload ended as a failure. */
    val failure: String?,
)

/**
 * Signs in against a real server, then uploads [source] through the production
 * [UploadRepository] — the real HTTP stack, the real retry loop, the real error mapping —
 * and reports how that upload ended.
 *
 * [source] is expected to fail part-way through its read. The point is the verdict the client gives
 * a file that cannot be read: a file error that is not retried, rather than "network unavailable"
 * three times over, which is what a bare `IOException` out of a request body used to become.
 *
 * Order-independent with the other probes (they share one server): it signs in when the server
 * already has users and sets up when it does not. Waits rather than hangs, and never throws.
 */
@Suppress("TooGenericExceptionCaught")
suspend fun probeUnreadableUpload(
    worker: Worker,
    dbName: String,
    email: String,
    password: String,
    source: FileSource,
): UnreadableUploadProbe {
    val app = browserGraph(worker, dbName)

    return try {
        val serverConfig = app.koin.get<ServerConfig>()
        if (!serverConfig.hasServerConfigured()) {
            serverConfig.setServerUrl(
                ServerUrl(seedServerUrlFromOrigin(stored = null, origin = window.location.origin)),
            )
        }

        val authSession = app.koin.get<AuthSession>()
        authSession.initializeAuthState()
        if (authSession.authState.value is AuthState.NeedsSetup) {
            app.koin.get<SetupViewModel>().onSetupSubmit(
                firstName = PROBE_FIRST_NAME,
                lastName = PROBE_LAST_NAME,
                email = email,
                password = password,
                passwordConfirm = password,
            )
        } else {
            app.koin.get<LoginViewModel>().onLoginSubmit(email = email, password = password)
        }

        val reachedAuthenticated =
            withTimeoutOrNull(AUTH_TIMEOUT) {
                authSession.authState.filterIsInstance<AuthState.Authenticated>().first()
            } != null
        if (!reachedAuthenticated) {
            return UnreadableUploadProbe(false, null, null, null, "never reached Authenticated")
        }

        // Longer than one honest attempt: a wrongly retried file would spend its back-off here, and
        // the probe should report that it retried rather than that it ran out of time.
        val ended =
            withTimeoutOrNull(UPLOAD_TIMEOUT) {
                app.koin
                    .get<UploadRepository>()
                    .upload(listOf(UploadCandidate(relPath = PROBE_REL_PATH, source = source)))
                    .first { it is UploadStep.Failed || it is UploadStep.Done }
            }

        when (ended) {
            is UploadStep.Failed -> {
                UnreadableUploadProbe(true, ended.error.code, ended.error.isRetryable, ended.error.message, null)
            }

            is UploadStep.Done -> {
                UnreadableUploadProbe(
                    true,
                    null,
                    null,
                    null,
                    "the upload finished; the file was meant to be unreadable",
                )
            }

            else -> {
                UnreadableUploadProbe(true, null, null, null, "the upload neither failed nor finished in time")
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        UnreadableUploadProbe(false, null, null, null, "probe threw: ${e.message}")
    } finally {
        app.close()
    }
}

private val AUTH_TIMEOUT = 20.seconds

private val UPLOAD_TIMEOUT = 40.seconds

private const val PROBE_REL_PATH = "probe/book.m4b"
private const val PROBE_FIRST_NAME = "Probe"
private const val PROBE_LAST_NAME = "Admin"
