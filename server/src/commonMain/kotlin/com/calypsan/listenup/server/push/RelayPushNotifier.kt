@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.push

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.push.PushPayload
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * [PushNotifier] backed by the ListenUp push relay. Resolves the user's live device tokens,
 * fans the encoded [PushPayload] out to the relay in one call, then reconciles the relay's
 * per-token verdicts: `invalid` tokens are deleted, `retryable` tokens get a single batched
 * retry, and `unsupported` is left alone (no delete, no retry — nothing actionable). A
 * transport failure (relay unreachable) is retried once as a whole batch, then silently
 * dropped — push is best-effort by design (see [PushNotifier] KDoc).
 */
class RelayPushNotifier(
    private val db: ListenUpDatabase,
    private val relay: PushRelayClient,
    private val settings: ServerSettingsRepository,
    private val clock: Clock,
) : PushNotifier {
    private val log = loggerFor<RelayPushNotifier>()

    override suspend fun notify(
        userId: String,
        payload: PushPayload,
    ) {
        if (!settings.pushNotificationsEnabled()) return
        val rows =
            suspendTransaction(db) {
                db.pushTokensQueries.selectLiveForUser(userId, clock.now().toEpochMilliseconds()).executeAsList()
            }
        // Rows store the PushPlatform enum name ("ANDROID"); the relay protocol speaks lowercase.
        fanOut(
            tokens = rows.map { PushRelayClient.RelayToken(platform = it.platform.lowercase(), token = it.token) },
            payload = payload,
        ) { dead -> suspendTransaction(db) { dead.forEach { db.pushTokensQueries.deleteByToken(it) } } }
    }

    override suspend fun notifyWatch(
        kind: PushWatchKind,
        key: String,
        payload: PushPayload,
    ) {
        if (!settings.pushNotificationsEnabled()) return
        val rows =
            suspendTransaction(db) {
                db.pushWatchTokensQueries
                    .selectLiveForKey(kind.wire, key, clock.now().toEpochMilliseconds())
                    .executeAsList()
            }
        fanOut(
            tokens = rows.map { PushRelayClient.RelayToken(platform = it.platform.lowercase(), token = it.token) },
            payload = payload,
        ) { dead -> suspendTransaction(db) { dead.forEach { db.pushWatchTokensQueries.deleteByToken(it) } } }
    }

    /**
     * The shared relay fan-out: encode once, send, retry the whole batch once on transport
     * failure, delete provider-reported dead tokens via [deleteDead], retry `retryable`
     * verdicts once. Token-set resolution and eviction differ per audience; everything after
     * is identical.
     */
    private suspend fun fanOut(
        tokens: List<PushRelayClient.RelayToken>,
        payload: PushPayload,
        deleteDead: suspend (List<String>) -> Unit,
    ) {
        if (tokens.isEmpty()) return
        val payloadJson = contractJson.encodeToJsonElement(PushPayload.serializer(), payload)
        val collapseKey = collapseKeyFor(payload)

        val response =
            try {
                attempt(tokens, payloadJson, collapseKey)
                    ?: run {
                        delay(RETRY_DELAY)
                        attempt(tokens, payloadJson, collapseKey)
                    }
            } catch (e: RelaySenderCredentialRejected) {
                // ERROR, not warn, and no retry: this is not a blip. Every push will fail
                // identically until someone sets the credential, and push's own best-effort
                // contract means nothing else will ever surface it. The message names the fix.
                log.error(e) {
                    "Relay rejected this server's sender credential — push is DISABLED until " +
                        "LISTENUP_PUSH_SENDER_TOKEN is set to the relay's expected value."
                }
                return
            }
                ?: run {
                    log.warn { "push dropped after retry: type=${payload::class.simpleName} tokens=${tokens.size}" }
                    return
                }
        val invalid = response.results.filter { it.status == "invalid" }.map { it.token }
        if (invalid.isNotEmpty()) {
            deleteDead(invalid)
        }
        val retryable =
            response.results
                .filter { it.status == "retryable" }
                .mapNotNull { r -> tokens.firstOrNull { it.token == r.token } }
        if (retryable.isNotEmpty()) {
            delay(RETRY_DELAY)
            attempt(retryable, payloadJson, collapseKey) // single retry; outcome accepted as-is
        }
    }

    private suspend fun attempt(
        tokens: List<PushRelayClient.RelayToken>,
        payloadJson: JsonElement,
        collapseKey: String?,
    ): PushRelayClient.RelayResponse? =
        try {
            relay.send(tokens, payloadJson, collapseKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RelaySenderCredentialRejected) {
            // Rethrown, not swallowed: retrying a refused credential just fails again, and the
            // caller logs it once with the remedy.
            throw e
        } catch (e: Exception) {
            // Never log token/payload contents — error class name only.
            log.debug { "relay attempt failed: ${e::class.simpleName}" }
            null
        }

    private fun collapseKeyFor(payload: PushPayload): String? =
        when (payload) {
            is PushPayload.CampfireInvite -> "campfire_invite:${payload.campfireId}"

            is PushPayload.TestNotification -> null

            // One notification per decided registration: a re-decide replaces, never stacks.
            is PushPayload.RegistrationDecision -> "registration_decision:${payload.userId}"

            // One per WAITING registrant, not one per admin — the key is the pending user, and
            // every admin's device collapses on the same one. A second push about the same person
            // (a retry, or a re-registration) replaces the first rather than stacking.
            is PushPayload.RegistrationApproval -> "registration_approval:${payload.userId}"
        }

    private companion object {
        val RETRY_DELAY = 2.seconds
    }
}
