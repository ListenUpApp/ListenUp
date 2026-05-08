package com.calypsan.listenup.api.streaming

import com.calypsan.listenup.api.error.AppError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Envelope for kotlinx.rpc server-pushed [kotlinx.coroutines.flow.Flow] streams.
 *
 * Replaces success-only `Flow<T>` so in-flight failures cross the wire as a
 * typed value instead of a thrown exception (which kotlinx.rpc 0.10.x
 * serialises as `SerializedException` with a leaked stacktrace).
 *
 * Server side: the KSP-generated `<Service>Guarded` decorator wraps every
 * streaming method's result with `.catch { e -> emit(Error(InternalError(...))) }`.
 * Service implementations emit [Data] values directly.
 *
 * Client side: consume with a `when` over the three variants. [Complete]
 * is reserved for explicit terminal markers; cooperative completion still
 * happens via `Flow` collection ending normally.
 */
@Serializable
sealed interface RpcEvent<out T> {
    /** Successful event payload. */
    @Serializable
    @SerialName("RpcEvent.Data")
    data class Data<T>(
        val value: T,
    ) : RpcEvent<T>

    /** Server-side error captured by the guard. Carries a typed [AppError] (usually [com.calypsan.listenup.api.error.InternalError]). */
    @Serializable
    @SerialName("RpcEvent.Error")
    data class Error(
        val error: AppError,
    ) : RpcEvent<Nothing>

    /** Optional explicit terminal marker. Most flows end via normal collection completion. */
    @Serializable
    @SerialName("RpcEvent.Complete")
    data object Complete : RpcEvent<Nothing>
}
