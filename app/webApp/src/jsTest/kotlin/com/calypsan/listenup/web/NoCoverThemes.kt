package com.calypsan.listenup.web

import com.calypsan.listenup.client.domain.model.CoverThemeStyle
import com.calypsan.listenup.client.domain.model.CoverThemes
import com.calypsan.listenup.client.domain.repository.CoverThemeRepository
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** A cover colour source that knows no cover: every hero stays neutral. For `WebGraph`s in specs. */
internal object NoCoverThemes : CoverThemeRepository {
    override fun cached(
        bookId: BookId,
        coverHash: String?,
        style: CoverThemeStyle,
    ): CoverThemes? = null

    override fun observe(
        bookId: BookId,
        coverHash: String?,
        style: CoverThemeStyle,
    ): Flow<CoverThemes?> = flowOf(null)
}
