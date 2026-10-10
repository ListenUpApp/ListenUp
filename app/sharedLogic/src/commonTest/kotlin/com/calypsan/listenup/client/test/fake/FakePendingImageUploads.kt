package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.client.domain.repository.PendingImageKind
import com.calypsan.listenup.client.domain.repository.PendingImageUploads

/**
 * In-memory [PendingImageUploads]: the set of images currently queued, as a save path left it. With
 * [canQueue] false it behaves like a platform that keeps no image file: nothing is ever queued.
 */
internal class FakePendingImageUploads(
    private val canQueue: Boolean = true,
) : PendingImageUploads {
    val pending = mutableSetOf<Pair<PendingImageKind, String>>()

    override suspend fun record(
        kind: PendingImageKind,
        entityId: String,
    ): Boolean {
        if (!canQueue) return false
        pending += kind to entityId
        return true
    }

    override suspend fun forget(
        kind: PendingImageKind,
        entityId: String,
    ) {
        pending -= kind to entityId
    }
}
