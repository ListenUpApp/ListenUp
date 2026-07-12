package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.SyncControl

/*
 * The refreshed tier: content-free control frames the server pushes to prompt a client
 * re-fetch. Each declares its trigger and refresh strategy — the whole refresh rulebook
 * in one file. Engine/lifecycle controls (`CursorStale`, `StreamError`, `AccessChanged`,
 * `UserDeleted`, `LibraryDataChanged`) are NOT here — they stay engine callbacks.
 */

/**
 * Presence changed: ping the signal the social repos (currently-listening, book-readers) collect.
 *
 * [refreshOnAccessChanged] is `true` because those RPCs are ACL-filtered at read time — an access
 * change (a collection share granted or revoked) changes which sessions the caller may see, so it is
 * a presence-visibility change that must re-fire this ping.
 */
internal fun presenceDomain(ping: () -> Unit): RefreshedDomain =
    RefreshedDomain(
        trigger = SyncControl.ActiveSessionsChanged::class,
        refresh = RefreshStrategy.Ping(ping),
        refreshOnAccessChanged = true,
    )

/**
 * Discoverable campfires (co-listening sessions) changed: ping the signal
 * `CampfireDiscoveryRepository` collects to re-fetch `listOpenSessions()` — the book-detail live
 * badge and the Discover "Live now" row.
 *
 * [refreshOnAccessChanged] is `true` for the same reason as [presenceDomain]: `listOpenSessions()`
 * is ACL-filtered at read time, so a collection grant/revoke changes which books' campfires the
 * caller may discover.
 */
internal fun campfireDomain(ping: () -> Unit): RefreshedDomain =
    RefreshedDomain(
        trigger = SyncControl.CampfiresChanged::class,
        refresh = RefreshStrategy.Ping(ping),
        refreshOnAccessChanged = true,
    )

/** Server info changed (admin edited name / remote URL): re-fetch getServerInfo (persists the remote-URL fallback). */
internal fun serverInfoDomain(refetch: suspend () -> Unit): RefreshedDomain =
    RefreshedDomain(
        trigger = SyncControl.ServerInfoChanged::class,
        refresh = RefreshStrategy.Refetch(refetch),
    )

/** Preferences changed on another device: re-fetch getMyPreferences (write-through into Room). */
internal fun preferencesDomain(refetch: suspend () -> Unit): RefreshedDomain =
    RefreshedDomain(
        trigger = SyncControl.PreferencesChanged::class,
        refresh = RefreshStrategy.Refetch(refetch),
    )
