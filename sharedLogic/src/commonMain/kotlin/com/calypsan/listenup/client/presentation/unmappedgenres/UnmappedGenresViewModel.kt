package com.calypsan.listenup.client.presentation.unmappedgenres

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.UnmappedStringSummary
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.core.fallbackTo
import com.calypsan.listenup.client.domain.repository.GenreRepository
import com.calypsan.listenup.client.presentation.error.userMessageFor
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}
private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

/**
 * ViewModel for the curator's unmapped-strings queue.
 *
 * The unmapped list comes from [GenreRepository.listUnmappedStrings] (RPC) on
 * load and after every successful mapping action — there is no Room mirror for
 * the queue (pending strings are a server-only concept). The genre tree comes
 * from the Room observation so the screen can present a picker.
 *
 * Mapping a raw string to a genre dispatches via
 * [GenreRepository.mapUnmappedToGenre]; on success the queue is refreshed.
 * Failures route through [ErrorBus] and surface as a transient `error` string
 * on [UnmappedGenresUiState.Ready].
 */
class UnmappedGenresViewModel(
    private val genreRepository: GenreRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    private val local = MutableStateFlow(LocalState())

    val state: StateFlow<UnmappedGenresUiState> =
        combine(
            genreRepository
                .observeAll()
                .map<List<Genre>, UnmappedGenresUiState> { genres ->
                    UnmappedGenresUiState.Ready(genres = genres)
                }.fallbackTo {
                    logger.error(it) { "Failed to observe genres for unmapped picker" }
                    UnmappedGenresUiState.Error(it.message ?: "Failed to load genres")
                },
            local,
        ) { upstream, l ->
            if (upstream is UnmappedGenresUiState.Ready) {
                upstream.copy(
                    unmapped = l.unmapped,
                    isLoadingUnmapped = l.isLoadingUnmapped,
                    isSaving = l.isSaving,
                    error = l.error,
                )
            } else {
                upstream
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = UnmappedGenresUiState.Loading,
        )

    init {
        refreshUnmapped()
    }

    /** Re-fetch the unmapped-strings queue. Called after every successful mapping action. */
    fun refreshUnmapped() {
        viewModelScope.launch {
            local.update { it.copy(isLoadingUnmapped = true, error = null) }
            val result = genreRepository.listUnmappedStrings()
            when (result) {
                is AppResult.Success -> {
                    local.update { it.copy(unmapped = result.data, isLoadingUnmapped = false) }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to fetch unmapped strings: ${result.error.message}" }
                    local.update {
                        it.copy(
                            isLoadingUnmapped = false,
                            error = userMessageFor(result.error),
                        )
                    }
                }
            }
        }
    }

    /**
     * Bind [rawString] to [genreId]. On success, refreshes the unmapped queue
     * so the just-mapped string disappears from the UI immediately.
     */
    fun mapToGenre(
        rawString: String,
        genreId: GenreId,
    ) {
        viewModelScope.launch {
            local.update { it.copy(isSaving = true, error = null) }
            when (val result = genreRepository.mapUnmappedToGenre(rawString, genreId)) {
                is AppResult.Success -> {
                    refreshUnmapped()
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to map '$rawString' to genre: ${result.error.message}" }
                    local.update { it.copy(error = userMessageFor(result.error)) }
                }
            }
            local.update { it.copy(isSaving = false) }
        }
    }

    /** Clear the transient inline error. */
    fun clearError() {
        local.update { it.copy(error = null) }
    }

    /** Action-mutated fields combined with the Room-observed genre tree. */
    private data class LocalState(
        val unmapped: List<UnmappedStringSummary> = emptyList(),
        val isLoadingUnmapped: Boolean = false,
        val isSaving: Boolean = false,
        val error: String? = null,
    )
}

/** Sealed UiState for the unmapped-strings curator screen. */
sealed interface UnmappedGenresUiState {
    data object Loading : UnmappedGenresUiState

    /**
     * Genres + unmapped queue have loaded. `isLoadingUnmapped` covers the
     * RPC refresh; `isSaving` covers an in-flight per-string mapping.
     */
    data class Ready(
        val genres: List<Genre> = emptyList(),
        val unmapped: List<UnmappedStringSummary> = emptyList(),
        val isLoadingUnmapped: Boolean = false,
        val isSaving: Boolean = false,
        val error: String? = null,
    ) : UnmappedGenresUiState

    /** Terminal state when the observe pipeline fails. */
    data class Error(
        val message: String,
    ) : UnmappedGenresUiState
}
