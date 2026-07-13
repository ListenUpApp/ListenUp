package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.api.result.AppResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.ValidationField
import com.calypsan.listenup.client.domain.model.InviteInfo
import com.calypsan.listenup.client.domain.usecase.admin.CreateInviteUseCase
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * ViewModel for the create invite screen.
 *
 * Handles form validation and invite creation.
 */
class CreateInviteViewModel(
    private val createInviteUseCase: CreateInviteUseCase,
) : ViewModel() {
    val state: StateFlow<CreateInviteUiState>
        field = MutableStateFlow<CreateInviteUiState>(CreateInviteUiState.Ready())

    fun createInvite(
        email: String,
        role: String,
        expiresInDays: Int,
    ) {
        viewModelScope.launch {
            updateReady { it.copy(status = CreateInviteStatus.Submitting) }

            when (val result = createInviteUseCase(email, role, expiresInDays)) {
                is AppResult.Success -> {
                    updateReady { it.copy(status = CreateInviteStatus.Success(result.data)) }
                }

                is AppResult.Failure -> {
                    val errorType = classifyError(result.error)
                    updateReady { it.copy(status = CreateInviteStatus.Error(errorType)) }
                }
            }
        }
    }

    fun clearError() {
        val ready = state.value as? CreateInviteUiState.Ready ?: return
        if (ready.status is CreateInviteStatus.Error) {
            updateReady { it.copy(status = CreateInviteStatus.Idle) }
        }
    }

    fun reset() {
        state.value = CreateInviteUiState.Ready()
    }

    /**
     * Apply [transform] to state only if it is currently [CreateInviteUiState.Ready].
     * No-ops when state is [CreateInviteUiState.Loading] or [CreateInviteUiState.Error].
     */
    private fun updateReady(transform: (CreateInviteUiState.Ready) -> CreateInviteUiState.Ready) {
        state.update { current ->
            if (current is CreateInviteUiState.Ready) transform(current) else current
        }
    }

    /**
     * Classify a [Failure]'s [AppError] into the UI's error type by *type*, not by
     * substring-matching the message text. An earlier implementation matched on
     * `result.message` substrings — that worked for the [ValidationError] cases
     * (constructor-supplied message text travels through verbatim) but silently fell
     * through for "already exists" / "conflict" (server 409 → `TransportError.Server4xx`
     * has body-level message `"Request rejected by server (HTTP 409)."` — no "conflict"
     * substring). The type-pattern shape below preserves the validation sub-classification via the
     * typed [ValidationError.field] discriminator (never a message substring) and replaces the
     * brittle bits.
     */
    private fun classifyError(error: AppError): CreateInviteErrorType {
        // debugInfo is per-instance technical detail (and, post-guard, null on the wire for guard
        // errors). It is for LOGS, never the UI — surface the user-facing `message` constant instead.
        logger.warn { "Create-invite failed: [${error.code}] cid=${error.correlationId} debug=${error.debugInfo}" }
        return when (error) {
            is ValidationError -> {
                if (error.field == ValidationField.EMAIL) {
                    CreateInviteErrorType.ValidationError(CreateInviteField.EMAIL)
                } else {
                    CreateInviteErrorType.ServerError(error.message)
                }
            }

            is TransportError.Server4xx -> {
                if (error.statusCode == HTTP_CONFLICT) {
                    CreateInviteErrorType.EmailInUse
                } else {
                    CreateInviteErrorType.ServerError(error.message)
                }
            }

            is TransportError.NetworkUnavailable, is TransportError.Timeout -> {
                CreateInviteErrorType.NetworkError(error.message)
            }

            else -> {
                CreateInviteErrorType.ServerError(error.message)
            }
        }
    }

    companion object {
        private const val HTTP_CONFLICT = 409
    }
}

/**
 * UI state for the Create Invite screen.
 *
 * Sealed hierarchy — this VM is a command-driven form with no async initial
 * load, so it enters [Ready] immediately at construction. [Loading] and
 * [Error] are present for sealed-hierarchy symmetry with other VMs; form
 * submission outcomes (including validation errors) flow through
 * [Ready.status].
 */
sealed interface CreateInviteUiState {
    /** Unused by this VM; present for hierarchy symmetry. */
    data object Loading : CreateInviteUiState

    /** Form ready for input; [status] tracks submission lifecycle. */
    data class Ready(
        val status: CreateInviteStatus = CreateInviteStatus.Idle,
    ) : CreateInviteUiState

    /** Unused by this VM; present for hierarchy symmetry. */
    data class Error(
        val message: String,
    ) : CreateInviteUiState
}

sealed interface CreateInviteStatus {
    data object Idle : CreateInviteStatus

    data object Submitting : CreateInviteStatus

    /** Invite was created; carries the server-issued [invite] details for display. */
    data class Success(
        val invite: InviteInfo,
    ) : CreateInviteStatus

    /** Invite creation failed; [type] determines which UI message and field highlight to show. */
    data class Error(
        val type: CreateInviteErrorType,
    ) : CreateInviteStatus
}

sealed interface CreateInviteErrorType {
    /** Client-side validation failed for [field]; the form highlights that input. */
    data class ValidationError(
        val field: CreateInviteField,
    ) : CreateInviteErrorType

    data object EmailInUse : CreateInviteErrorType

    /** Network transport failure (offline / timeout); [detail] is the underlying message if any. */
    data class NetworkError(
        val detail: String?,
    ) : CreateInviteErrorType

    /** Server-side failure not covered by [ValidationError] or [EmailInUse]; [detail] is the server message if any. */
    data class ServerError(
        val detail: String?,
    ) : CreateInviteErrorType
}

/** Form field highlighted by [CreateInviteErrorType.ValidationError]. */
enum class CreateInviteField {
    EMAIL,
}
