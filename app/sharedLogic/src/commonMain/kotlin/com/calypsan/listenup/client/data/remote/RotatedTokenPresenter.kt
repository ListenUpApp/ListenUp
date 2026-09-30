package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.AuthServiceAuthed
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * Carries a freshly rotated access token to the server at once.
 *
 * The server tells a lost refresh reply from a stolen token by whether an access token minted at or
 * after the latest rotation has reached it (its lost-reply rule, `SessionService.rotate`). Most
 * refreshes present the new token by construction — the bearer plugin retries the request that
 * 401'd, and RPC 401 recovery reconnects. A rotation made for playback does neither: the RPC socket
 * keeps the principal it bound at its upgrade, so the new token could go unpresented for hours, and
 * a process death in that window (Android Auto starts a book, then Android kills it) leaves the
 * rotation unconfirmed for good.
 *
 * So after such a rotation this retires the authed auth channel's socket and makes one cheap call on
 * it: the reconnect's upgrade carries the new token (a header natively, a freshly minted ticket in a
 * browser). Only that channel is retired — the other channels keep their sockets, and each reconnects
 * with the current token whenever it next needs to. Fire-and-forget on [scope]: playback never waits
 * on it, and a failure only means the confirmation comes with the next socket instead.
 */
internal class RotatedTokenPresenter(
    private val authedChannel: RpcChannel<AuthServiceAuthed>,
    private val scope: CoroutineScope,
) {
    /** Present the current access token to the server on a fresh connection. */
    fun present() {
        scope.launch {
            try {
                authedChannel.retire()
                authedChannel.call(idempotent = true) { it.listSessions() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Could not present the rotated token; the next connection will" }
            }
        }
    }
}
