package com.calypsan.listenup.client.presentation.seriesdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * ViewModel for the Series Detail screen.
 *
 * Observes series data reactively via `observeSeriesWithBooks` so the UI
 * tracks sync-driven updates without re-loading. The screen supplies the
 * series id via [loadSeries]; the flow pipeline uses `flatMapLatest` to
 * swap the upstream when the id changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeriesDetailViewModel(
    private val seriesRepository: SeriesRepository,
    private val imageRepository: ImageRepository,
) : ViewModel() {
    private val seriesIdFlow = MutableStateFlow<String?>(null)

    val state: StateFlow<SeriesDetailUiState> =
        seriesIdFlow
            .flatMapLatest { id ->
                if (id == null) {
                    flowOf(SeriesDetailUiState.Idle)
                } else {
                    seriesRepository
                        .observeSeriesWithBooks(id)
                        .map { seriesWithBooks ->
                            if (seriesWithBooks != null) {
                                val books = seriesWithBooks.booksSortedBySequence()
                                val totalDuration = books.sumOf { it.duration }.milliseconds
                                val coverPath = resolveCoverPath(id, books)

                                val ready: SeriesDetailUiState =
                                    SeriesDetailUiState.Ready(
                                        seriesId = id,
                                        seriesName = seriesWithBooks.series.name,
                                        seriesDescription = seriesWithBooks.series.description,
                                        coverPath = coverPath,
                                        featuredBookId = books.firstOrNull()?.id?.value,
                                        totalDuration = totalDuration,
                                        books = books,
                                    )
                                ready
                            } else {
                                SeriesDetailUiState.Error("Series not found")
                            }
                        }.onStart { emit(SeriesDetailUiState.Loading) }
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = SeriesDetailUiState.Idle,
            )

    /** Set the series to observe. Safe to call repeatedly with the same id. */
    fun loadSeries(seriesId: String) {
        seriesIdFlow.value = seriesId
    }

    private fun resolveCoverPath(
        seriesId: String,
        books: List<BookListItem>,
    ): String? {
        if (imageRepository.seriesCoverExists(seriesId)) {
            return imageRepository.getSeriesCoverPath(seriesId)
        }
        return books.firstOrNull()?.coverPath
    }
}

/**
 * UI state for the Series Detail screen.
 */
sealed interface SeriesDetailUiState {
    /** No series selected (pre-[SeriesDetailViewModel.loadSeries]). */
    data object Idle : SeriesDetailUiState

    /** Upstream has not yet produced data for the selected series. */
    data object Loading : SeriesDetailUiState

    /** Series and books loaded. */
    data class Ready(
        val seriesId: String,
        val seriesName: String,
        val seriesDescription: String?,
        val coverPath: String?,
        val featuredBookId: String?,
        val totalDuration: Duration,
        val books: List<BookListItem>,
    ) : SeriesDetailUiState {
        fun formatTotalDuration(): String {
            val totalHours = totalDuration.inWholeHours
            val minutes = totalDuration.inWholeMinutes % 60
            return if (totalHours > 0) "${totalHours}h ${minutes}m" else "${minutes}m"
        }
    }

    /** Load failed. */
    data class Error(
        val message: String,
    ) : SeriesDetailUiState
}
