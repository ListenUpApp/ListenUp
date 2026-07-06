package com.calypsan.listenup.client.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response shape for the filesystem-browse surface.
 *
 * Lists directories available for selection as scan paths. Reconstructed
 * client-side by [com.calypsan.listenup.client.data.repository.AdminRepositoryImpl.browseFilesystem]
 * from the [com.calypsan.listenup.api.LibraryAdminService.browseFilesystem] RPC result.
 */
@Serializable
data class BrowseFilesystemResponse(
    @SerialName("path") val path: String,
    @SerialName("parent") val parent: String? = null,
    @SerialName("entries") val entries: List<DirectoryEntryResponse> = emptyList(),
    @SerialName("is_root") val isRoot: Boolean = false,
)

/**
 * A directory entry in the filesystem browser.
 */
@Serializable
data class DirectoryEntryResponse(
    @SerialName("name") val name: String,
    @SerialName("path") val path: String,
)
