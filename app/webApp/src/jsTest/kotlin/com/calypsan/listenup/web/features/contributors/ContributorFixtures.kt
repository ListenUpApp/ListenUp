package com.calypsan.listenup.web.features.contributors

import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.client.domain.model.Contributor
import com.calypsan.listenup.client.domain.model.ContributorWithBookCount
import com.calypsan.listenup.core.ContributorId

/**
 * One contributor row's worth of data — enough for a spec to drive the letter-grouping, role-chip
 * and row contracts without a database behind them. Shared rather than duplicated, the same call
 * `FakeLibrary.kt` and `BookDetailFixtures.kt` make for their own features.
 */
internal fun contributor(
    id: String,
    name: String,
    bookCount: Int = 1,
    imagePath: String? = null,
    imageFrame: ImageFrame? = null,
): ContributorWithBookCount =
    ContributorWithBookCount(
        contributor = Contributor(id = ContributorId(id), name = name, imagePath = imagePath, imageFrame = imageFrame),
        bookCount = bookCount,
    )
