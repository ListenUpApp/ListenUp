package com.calypsan.listenup.client.data.remote.model

import com.calypsan.listenup.client.core.ContributorId
import com.calypsan.listenup.client.core.SeriesId
import com.calypsan.listenup.client.data.local.db.ContributorEntity
import com.calypsan.listenup.client.data.local.db.SeriesEntity
import com.calypsan.listenup.client.util.parseToTimestampOrNow

fun SeriesResponse.toEntity(): SeriesEntity {
    val serverUpdatedAt = updatedAt.parseToTimestampOrNow()
    val serverCreatedAt = createdAt.parseToTimestampOrNow()

    return SeriesEntity(
        id = SeriesId(id),
        name = name,
        description = description,
        asin = asin,
        coverPath = coverImage?.path,
        coverBlurHash = coverImage?.blurHash,
        createdAt = serverCreatedAt,
        updatedAt = serverUpdatedAt,
    )
}

fun ContributorResponse.toEntity(): ContributorEntity {
    val serverUpdatedAt = updatedAt.parseToTimestampOrNow()
    val serverCreatedAt = createdAt.parseToTimestampOrNow()

    // Note: imagePath is intentionally null here. The server returns a relative URL
    // (e.g., "/api/v1/contributors/{id}/image") which is not a local file path.
    // Images must be downloaded separately and the local path set afterward.
    // Aliases are written separately through `contributor_aliases` by `ContributorPuller`.
    return ContributorEntity(
        id = ContributorId(id),
        name = name,
        sortName = sortName,
        asin = asin,
        description = biography,
        imagePath = null,
        imageBlurHash = imageBlurHash,
        website = website,
        birthDate = birthDate,
        deathDate = deathDate,
        createdAt = serverCreatedAt,
        updatedAt = serverUpdatedAt,
    )
}
