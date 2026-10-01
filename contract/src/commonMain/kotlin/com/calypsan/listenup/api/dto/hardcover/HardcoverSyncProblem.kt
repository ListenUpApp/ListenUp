package com.calypsan.listenup.api.dto.hardcover

import kotlinx.serialization.Serializable

/**
 * Why a connected user's sync with Hardcover isn't keeping up, worst first. A client shows one
 * constant sentence per value; the technical detail stays on the server, in its logs and its
 * `push_error` / `pull_error` columns.
 */
@Serializable
enum class HardcoverSyncProblem {
    /** The "Sync now" the user just asked for couldn't reach Hardcover. ListenUp keeps trying on its own. */
    SYNC_NOW_FAILED,

    /** Something ListenUp sent has failed past its retry cap. It keeps retrying every few hours. */
    PUSH_STALLED,

    /** Reading the user's Hardcover shelf has failed past its retry cap. It keeps retrying on schedule. */
    PULL_STALLED,
}
