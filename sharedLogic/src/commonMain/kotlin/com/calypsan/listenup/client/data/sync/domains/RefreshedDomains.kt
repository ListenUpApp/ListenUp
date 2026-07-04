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
