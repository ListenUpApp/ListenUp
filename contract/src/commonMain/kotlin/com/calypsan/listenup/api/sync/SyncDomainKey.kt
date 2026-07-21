@file:OptIn(ExperimentalObjCRefinement::class)

package com.calypsan.listenup.api.sync

import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC
import kotlinx.serialization.KSerializer

/**
 * Identity of one syncable domain: its wire [name] (the [SyncFrame.domain] value, the
 * `/api/v1/sync/{name}` path segment, and the client's cursor key) and the
 * [serializer] for its payload DTO.
 *
 * Read by BOTH sides — the server's `SqlSyncableRepository` registers under it and
 * the client's sync-domain catalog declares behavior against it — so a domain's wire
 * name exists in exactly one place. Renaming a constant in [SyncDomains] is a
 * compiler-walked refactor, never a cross-module string hunt.
 */
@HiddenFromObjC
class SyncDomainKey<T : Any>(
    /** Wire name: [SyncFrame.domain] value, sync-route path segment, cursor key. */
    val name: String,
    /** Serializer for the domain's payload DTO. */
    val serializer: KSerializer<T>,
)
