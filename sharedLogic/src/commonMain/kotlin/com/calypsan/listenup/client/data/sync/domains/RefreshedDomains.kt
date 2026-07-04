package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.SyncControl

/*
 * The nudge tier: content-free control frames the server pushes to prompt a client
 * re-fetch. Each declares its trigger and refresh strategy — the whole nudge rulebook
 * in one file. Engine/lifecycle controls (`CursorStale`, `StreamError`, `AccessChanged`,
 * `UserDeleted`, `LibraryDataChanged`) are NOT here — they stay engine callbacks.
 */

/** Presence changed: ping the signal the social repos (currently-listening, book-readers) collect. */
internal fun presenceDomain(ping: () -> Unit): RefreshedDomain =
    RefreshedDomain(
        trigger = SyncControl.ActiveSessionsChanged::class,
        refresh = RefreshStrategy.Ping(ping),
        // Presence pings on every lifecycle edge, and its collectors (currently-listening,
        // book-readers) refetch on subscribe — a dropped ping heals either way.
        recovery = NudgeRecovery.OnSubscribeAndReconcile(),
    )

/** Activity feed changed: ping the signal the activity feed collects. */
internal fun activityDomain(ping: () -> Unit): RefreshedDomain =
    RefreshedDomain(
        trigger = SyncControl.ActivityChanged::class,
        refresh = RefreshStrategy.Ping(ping),
        // The engine primes the activity feed into Room on every lifecycle edge (UI-independent),
        // so a dropped ActivityChanged heals without the Discover surface being open.
        recovery = NudgeRecovery.OnLifecycleReconcile,
    )

/** Server info changed (admin edited name / remote URL): re-fetch getServerInfo (persists the remote-URL fallback). */
internal fun serverInfoDomain(refetch: suspend () -> Unit): RefreshedDomain =
    RefreshedDomain(
        trigger = SyncControl.ServerInfoChanged::class,
        refresh = RefreshStrategy.Refetch(refetch),
        // TODO(Phase 3): fold this refetch into the lifecycle-reconcile pass. For now it heals only
        // on its control frame; the declared recovery pins the intended shape.
        recovery = NudgeRecovery.OnLifecycleReconcile,
    )

/** Preferences changed on another device: re-fetch getMyPreferences (write-through into Room). */
internal fun preferencesDomain(refetch: suspend () -> Unit): RefreshedDomain =
    RefreshedDomain(
        trigger = SyncControl.PreferencesChanged::class,
        refresh = RefreshStrategy.Refetch(refetch),
        // TODO(Phase 3): fold this refetch into the lifecycle-reconcile pass. For now it heals only
        // on its control frame; the declared recovery pins the intended shape.
        recovery = NudgeRecovery.OnLifecycleReconcile,
    )
