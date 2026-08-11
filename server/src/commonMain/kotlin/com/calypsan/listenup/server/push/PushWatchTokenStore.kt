@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.push

import com.calypsan.listenup.api.push.PushPlatform
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * The decision flows a pre-auth device can watch (#1068). [wire] is the persisted
 * `watch_kind` discriminator — stable, additive-only.
 */
enum class PushWatchKind(
    val wire: String,
) {
    /** A pending registration awaiting an admin decision; the watch key is the pending user id. */
    REGISTRATION("registration"),
}

/**
 * Persistence for pre-auth watch tokens: devices waiting on an admin decision register their
 * push token against the flow's unguessable handle (they have no session — that's why they're
 * waiting). Rows are re-upserted freely, capped per watch key so a churning client can't grow
 * unbounded fan-out, evicted the moment the decision is delivered, and TTL-swept as the
 * never-stranded backstop.
 */
class PushWatchTokenStore(
    private val db: ListenUpDatabase,
    private val clock: Clock,
) {
    /** Registers (or refreshes) [token] as a watcher of ([kind], [key]), enforcing the per-key cap. */
    suspend fun register(
        kind: PushWatchKind,
        key: String,
        token: String,
        platform: PushPlatform,
    ) {
        val now = clock.now().toEpochMilliseconds()
        suspendTransaction(db) {
            db.pushWatchTokensQueries.upsert(
                token = token,
                platform = platform.name,
                watch_kind = kind.wire,
                watch_key = key,
                now = now,
                expires_at = now + WATCH_TTL.inWholeMilliseconds,
            )
            db.pushWatchTokensQueries.deleteBeyondNewestForKey(
                watch_kind = kind.wire,
                watch_key = key,
                keep = MAX_TOKENS_PER_KEY,
            )
        }
    }

    /** Evicts every watcher of ([kind], [key]) — call right after the decision push. */
    suspend fun evict(
        kind: PushWatchKind,
        key: String,
    ) {
        suspendTransaction(db) {
            db.pushWatchTokensQueries.deleteForKey(watch_kind = kind.wire, watch_key = key)
        }
    }

    /** TTL sweep; runs alongside the session-expiry cleanup. */
    suspend fun sweepExpired() {
        suspendTransaction(db) {
            db.pushWatchTokensQueries.deleteExpired(clock.now().toEpochMilliseconds())
        }
    }

    companion object {
        /**
         * How long a watch row outlives its registration before the sweep collects it. Clients
         * re-upsert on every visit to the waiting screen, so a live waiter never ages out; a
         * week comfortably covers an approval that takes days.
         */
        val WATCH_TTL = 7.days

        /** Newest-N cap per watch key — bounds fan-out abuse from a handle-holder. */
        const val MAX_TOKENS_PER_KEY = 3L
    }
}
