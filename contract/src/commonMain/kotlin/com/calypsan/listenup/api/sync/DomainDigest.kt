@file:OptIn(ExperimentalObjCRefinement::class)

package com.calypsan.listenup.api.sync

import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Per-domain row-set fingerprint. Returned by `GET /api/v1/sync/<domain>/digest?cursor=<rev>`
 * and computed identically on both server and client over `(id, revision)` pairs of every
 * row in the domain with `revision <= cursor`, soft-deleted rows included.
 *
 * Algorithm: sort rows lexicographically by `id`, concatenate as `<id>|<revision>\n` per row,
 * SHA-256 the resulting bytes, format as `"sha256:<lowercase-hex>"`. Empty domain → `count = 0`,
 * `hash = ""`.
 *
 * Used by clients to detect drift cheaply — match `(count, hash)` between local and server;
 * mismatch triggers a full domain re-pull (`?since=0`).
 */
@HiddenFromObjC
@Serializable
data class DomainDigest(
    @SerialName("cursor")
    val cursor: Long,
    val count: Int,
    val hash: String,
)
