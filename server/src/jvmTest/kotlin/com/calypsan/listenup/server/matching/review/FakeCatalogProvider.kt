package com.calypsan.listenup.server.matching.review

import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.metadata.spi.BookCoreMeta
import com.calypsan.listenup.server.metadata.spi.BookCoreSource
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.metadata.spi.ChapterListMeta
import com.calypsan.listenup.server.metadata.spi.ChapterSource
import com.calypsan.listenup.server.metadata.spi.CoverMeta
import com.calypsan.listenup.server.metadata.spi.CoverSource
import com.calypsan.listenup.server.metadata.spi.GenreKind
import com.calypsan.listenup.server.metadata.spi.GenreLadderSource
import com.calypsan.listenup.server.metadata.spi.GenreMeta
import com.calypsan.listenup.server.metadata.spi.GenreSource
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.MoodSource
import com.calypsan.listenup.server.metadata.spi.SeriesMeta
import com.calypsan.listenup.server.metadata.spi.SeriesSource
import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * A catalogue with every book-side capability, answering from its fields, recording each identity it is asked
 * for. [core] may be a failure; [slow] delays every answer (virtual time in tests).
 */
internal class FakeCatalogProvider(
    override val id: MetadataProviderId,
    var core: AppResult<BookCoreMeta?> = AppResult.Success(null),
    var covers: List<CoverMeta> = emptyList(),
    var genres: List<String> = emptyList(),
    var ladders: List<List<String>> = emptyList(),
    var series: List<SeriesMeta> = emptyList(),
    var moods: List<String> = emptyList(),
    var chapters: ChapterListMeta? = null,
    var slow: Duration = Duration.ZERO,
) : BookCoreSource,
    CoverSource,
    GenreSource,
    GenreLadderSource,
    SeriesSource,
    MoodSource,
    ChapterSource {
    val asked = mutableListOf<BookIdentity>()

    private suspend fun <T> answer(
        identity: BookIdentity,
        value: T,
    ): T {
        asked += identity
        if (slow > Duration.ZERO) delay(slow)
        return value
    }

    override suspend fun getBookCore(
        book: BookIdentity,
        locale: MetadataLocale,
        refresh: Boolean,
    ): AppResult<BookCoreMeta?> = answer(book, core)

    override suspend fun searchCovers(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<List<CoverMeta>> = answer(book, AppResult.Success(covers))

    override suspend fun getGenres(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<List<GenreMeta>?> = answer(book, AppResult.Success(genres.map { GenreMeta(it, GenreKind.GENRE) }))

    override suspend fun getGenreLadders(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<List<List<String>>?> = answer(book, AppResult.Success(ladders))

    override suspend fun getSeries(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<List<SeriesMeta>?> = answer(book, AppResult.Success(series))

    override suspend fun getMoods(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<List<String>?> = answer(book, AppResult.Success(moods))

    override suspend fun getChapters(
        book: BookIdentity,
        locale: MetadataLocale,
        refresh: Boolean,
    ): AppResult<ChapterListMeta?> = answer(book, AppResult.Success(chapters))
}
