package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase

/**
 * A bare `hardcover_connections` row for [userId] — what the outbox requires before it queues a push — for
 * rigs that exercise the outbox or the executor and never decrypt a token (the token columns hold
 * placeholders, not ciphertext).
 */
internal fun ListenUpDatabase.seedHardcoverConnectionRow(userId: String) {
    hardcoverConnectionsQueries.upsertConnection(
        user_id = userId,
        hc_user_id = 42L,
        hc_username = "reader",
        access_token_enc = "unused",
        access_expires_at = Long.MAX_VALUE,
        refresh_token_enc = "unused",
        refresh_expires_at = Long.MAX_VALUE,
        scopes = HARDCOVER_SCOPES,
        connected_at = 0L,
    )
}
