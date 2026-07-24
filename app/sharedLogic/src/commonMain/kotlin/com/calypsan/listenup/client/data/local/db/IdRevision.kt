package com.calypsan.listenup.client.data.local.db

/** Room projection of a syncable row's identity + revision, for digest computation. */
internal data class IdRevision(
    val id: String,
    val revision: Long,
)
