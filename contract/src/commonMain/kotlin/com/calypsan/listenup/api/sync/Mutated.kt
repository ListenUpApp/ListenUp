package com.calypsan.listenup.api.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A mutation's return envelope: the produced [value] plus the sync [frames] the write emitted.
 *
 * This is what makes an RPC mutation *read-your-writes* for free. The server already builds the
 * exact [SyncEvent] it broadcasts to the firehose for every syncable write; a mutation that returns
 * [Mutated] hands those events back to the originating device as [SyncFrame]s so it can apply its
 * own result the instant the call returns — through the same generic, revision-guarded,
 * idempotent apply pipeline that consumes every other client's echo. The originating device no
 * longer depends on the live firehose delivering its own change (a gap that left the calling client
 * stale until restart); the later firehose echo for the same revision simply no-ops.
 *
 * [frames] is a list because a single mutation can touch more than one domain at once (a book match
 * enriches the book *and* its contributors), and each frame routes to its own domain handler. An
 * empty list is valid — a mutation that produced no syncable change carries no frames.
 */
@Serializable
data class Mutated<out T>(
    /** The value the mutation produced — what the caller would have received without echo-apply. */
    @SerialName("value") val value: T,
    /** The sync frames this mutation emitted, applied client-side for read-your-writes. */
    val frames: List<SyncFrame> = emptyList(),
)
