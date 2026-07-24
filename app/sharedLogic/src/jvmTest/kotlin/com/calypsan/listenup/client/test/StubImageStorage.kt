package com.calypsan.listenup.client.test

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.ImageStorage
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock

/**
 * A no-op [ImageStorage] for tests that construct a books sync handler but don't care about its
 * cover-cache-invalidation side effect. The handler only calls [ImageStorage.deleteCover]; that is
 * stubbed to succeed. Tests that assert on the deletion build their own recording mock instead.
 */
fun stubImageStorage(): ImageStorage =
    mock {
        everySuspend { deleteCover(any()) } returns AppResult.Success(Unit)
        everySuspend { deleteContributorImage(any()) } returns AppResult.Success(Unit)
        everySuspend { deleteSeriesCover(any()) } returns AppResult.Success(Unit)
    }
