package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.CombinedScore
import com.calypsan.listenup.client.domain.model.ExternalRating
import com.calypsan.listenup.client.domain.model.ListenerAverage
import com.calypsan.listenup.client.domain.model.ListenerRating
import com.calypsan.listenup.client.domain.repository.BookRatingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory [BookRatingRepository]: every book shares [ratings]; [rate] records the call in [rated] and answers
 * [rateResult]. The rest answer empty flows or a success, as no rig reads them.
 */
internal class FakeBookRatingRepository(
    val ratings: MutableStateFlow<List<ListenerRating>> = MutableStateFlow(emptyList()),
    var rateResult: AppResult<Unit> = AppResult.Success(Unit),
) : BookRatingRepository {
    /** One entry per [rate] call, in order. */
    val rated = mutableListOf<RatingWrite>()

    /** The arguments of a [rate] call. */
    data class RatingWrite(
        val bookId: String,
        val halfStars: Int,
        val note: String?,
    )

    override fun observeForBook(bookId: String): Flow<List<ListenerRating>> = ratings

    override fun observeAverages(): Flow<Map<String, ListenerAverage>> = flowOf(emptyMap())

    override suspend fun rate(
        bookId: String,
        halfStars: Int,
        note: String?,
    ): AppResult<Unit> {
        rated += RatingWrite(bookId, halfStars, note)
        return rateResult
    }

    override suspend fun clear(bookId: String): AppResult<Unit> = AppResult.Success(Unit)

    override fun observeExternalForBook(bookId: String): Flow<List<ExternalRating>> = flowOf(emptyList())

    override fun observeCombinedScores(): Flow<Map<String, CombinedScore>> = flowOf(emptyMap())

    override fun observeCombinedScore(bookId: String): Flow<CombinedScore?> = flowOf(null)

    override suspend fun refreshExternal(bookId: String): AppResult<Unit> = AppResult.Success(Unit)

    override fun observeExternalCheck(bookId: String): Flow<Boolean> = flowOf(false)
}
