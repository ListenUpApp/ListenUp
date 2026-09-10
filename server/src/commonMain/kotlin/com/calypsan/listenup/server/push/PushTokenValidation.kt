package com.calypsan.listenup.server.push

/**
 * Wire cap on a push token's length (SEC-05). The relay rejects an ENTIRE send batch when any
 * token in it exceeds this cap — one oversized row silently suppresses the push for every
 * legitimate watcher sharing that batch. Shared by the authenticated path
 * ([com.calypsan.listenup.server.api.PushServiceImpl.registerToken]) and the pre-auth
 * registration-watch path ([com.calypsan.listenup.server.auth.AuthServiceImpl.registerRegistrationWatchToken]),
 * so both paths reject an oversized token before it ever reaches the relay.
 */
const val MAX_PUSH_TOKEN_LENGTH = 4096

/**
 * True when [token] is well-formed enough to persist: non-blank and within
 * [MAX_PUSH_TOKEN_LENGTH]. Shared validation shape reused by both push-token registration paths
 * (see [MAX_PUSH_TOKEN_LENGTH]'s KDoc); callers needing a user-facing reason build their own
 * [com.calypsan.listenup.api.error.ValidationError] around the same two checks.
 */
fun isValidPushToken(token: String): Boolean = token.isNotBlank() && token.length <= MAX_PUSH_TOKEN_LENGTH
