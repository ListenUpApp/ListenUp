package com.calypsan.listenup.client.download

/**
 * Provides device storage metrics for the storage management UI.
 *
 * Extracted as an interface so that [DownloadFileManager] (an `expect class`) can be
 * adapted into a testable seam without requiring Mokkery to mock a final class.
 */
interface StorageSpaceProvider {
    /** Total bytes used by downloads. */
    fun calculateStorageUsed(): Long

    /** Bytes of device storage available for new downloads. */
    fun getAvailableSpace(): Long
}

/**
 * Adapts [DownloadFileManager] to the [StorageSpaceProvider] interface.
 */
class DownloadFileManagerStorageAdapter(
    private val fileManager: DownloadFileManager,
) : StorageSpaceProvider {
    override fun calculateStorageUsed(): Long = fileManager.calculateStorageUsed()

    override fun getAvailableSpace(): Long = fileManager.getAvailableSpace()
}
