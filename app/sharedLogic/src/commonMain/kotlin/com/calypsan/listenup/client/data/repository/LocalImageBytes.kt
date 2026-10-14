package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.core.IODispatcher
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/** The committed local photo of contributor [contributorId], or null when this device holds none. Read off the main thread. */
internal suspend fun ImageStorage.readContributorImageBytes(contributorId: String): ByteArray? {
    if (!contributorImageExists(contributorId)) return null
    val path = getContributorImagePath(contributorId)
    return withContext(IODispatcher) { SystemFileSystem.source(Path(path)).buffered().use { it.readByteArray() } }
}
