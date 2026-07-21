@file:OptIn(ExperimentalObjCRefinement::class)

package com.calypsan.listenup.api.sync

import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.jvm.JvmInline
import kotlin.native.HiddenFromObjC
import kotlinx.serialization.Serializable

/**
 * Type-safe wrapper around the global sync revision counter.
 *
 * Clients persist this across reconnects and pass it as `sinceRevision` on stream
 * resume or as the `?since=` query parameter on REST catch-up. The underlying
 * type is [Long]; the value class is for call-site clarity only.
 */
@HiddenFromObjC
@Serializable
@JvmInline
value class SyncCursor(
    val revision: Long,
)
