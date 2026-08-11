package com.calypsan.listenup.server.push

import com.calypsan.listenup.api.push.PushPayload

/**
 * THE seam services call to push-notify a user. Best-effort by design: push is
 * a wake-up accelerant; the event's truth lives in queryable synced domains
 * (Never Stranded). Implementations MUST NOT throw, MUST respect the
 * pushNotificationsEnabled admin toggle at send time, and MUST NOT log payload
 * contents or tokens (error class names, counts, and correlation ids only).
 * Intended callers: Campfire's invite notifier, future registration approvals.
 */
interface PushNotifier {
    /** Fire-and-forget: resolves the user's live device tokens and sends [payload] to each. */
    suspend fun notify(
        userId: String,
        payload: PushPayload,
    )

    /**
     * Fire-and-forget to the pre-auth watchers of ([kind], [key]) — devices that registered a
     * watch token while waiting on an admin decision (#1068). Same best-effort contract as
     * [notify]; eviction of the watch rows after a decision is the caller's separate,
     * unconditional step ([PushWatchTokenStore.evict]) — it must happen even when push is
     * disabled or delivery fails.
     */
    suspend fun notifyWatch(
        kind: PushWatchKind,
        key: String,
        payload: PushPayload,
    )
}

/** Bound when no relay URL is configured at all (forks without a relay). */
class NoOpPushNotifier : PushNotifier {
    override suspend fun notify(
        userId: String,
        payload: PushPayload,
    ) = Unit

    override suspend fun notifyWatch(
        kind: PushWatchKind,
        key: String,
        payload: PushPayload,
    ) = Unit
}
