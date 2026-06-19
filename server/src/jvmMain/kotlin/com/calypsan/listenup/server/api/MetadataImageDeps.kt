package com.calypsan.listenup.server.api

import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.metadata.ImageStorage
import kotlinx.io.files.Path

/**
 * Cohesive bundle of the image/cover storage surface used by [MetadataLookupServiceImpl].
 *
 * Groups the three collaborators that together form the metadata flow's cover-storage
 * dependency — remote image fetching ([imageStorage]), managed-cover persistence
 * ([coverImageStore]), and the on-disk image root ([imageHome]) — into a single injected
 * value so the service constructor stays cohesive rather than carrying three loose params.
 */
internal data class MetadataImageDeps(
    val imageStorage: ImageStorage,
    val coverImageStore: CoverImageStore,
    val imageHome: Path,
)
