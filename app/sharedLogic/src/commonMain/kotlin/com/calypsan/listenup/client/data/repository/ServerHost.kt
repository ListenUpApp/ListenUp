package com.calypsan.listenup.client.data.repository

/**
 * Host part of an absolute `http(s)` URL — the identity that matters for "is this the same server".
 *
 * Scheme, port and path are deliberately dropped: a port change or an http→https upgrade of the
 * same host is still the same server, and the credentials it issued stay valid there. A different
 * host is a different server, whatever else matches. Used by [SettingsRepositoryImpl.setServerUrl]
 * to decide whether the persisted session must be cleared, and by the invite claim flow to decide
 * whether a link-supplied address needs the user's confirmation before it is persisted.
 */
internal fun String.hostOfUrl(): String = substringAfter("://").substringBefore('/').substringBefore(':')
