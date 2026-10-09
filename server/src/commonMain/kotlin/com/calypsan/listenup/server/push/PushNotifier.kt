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
     * Fire-and-forget to [watchers]: devices that registered a watch token while waiting on an
     * admin decision (#1068), as the caller's eviction ([PushWatchTokenStore.evict]) handed them
     * over. Eviction comes first and is unconditional — it must happen even when push is disabled
     * or delivery fails — so the rows are gone by now and the push goes to these, never to a lookup.
     * Same best-effort contract as [notify].
     */
    suspend fun notifyWatchers(
        watchers: List<PushWatcher>,
        payload: PushPayload,
    )
}

/** Bound when no relay URL is configured at all (forks without a relay). */
class NoOpPushNotifier : PushNotifier {
    override suspend fun notify(
        userId: String,
        payload: PushPayload,
    ) = Unit

    override suspend fun notifyWatchers(
        watchers: List<PushWatcher>,
        payload: PushPayload,
    ) = Unit
}
