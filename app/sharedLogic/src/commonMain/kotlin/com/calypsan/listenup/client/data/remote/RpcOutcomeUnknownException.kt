package com.calypsan.listenup.client.data.remote

/**
 * Signals that an RPC frame was **sent** but its outcome could not be observed — the connection
 * dropped after delivery, while awaiting the response.
 *
 * kotlinx.rpc surfaces a post-delivery drop as a bare `CancellationException("Client cancelled")`
 * thrown from below when it closes the *pending* (already-sent) request channel. [RpcProxyCache]
 * must NOT auto-retry this: the server may have received, handled, and committed a non-idempotent
 * mutation, so a retry would double-apply it. It also must not re-raise the raw
 * `CancellationException` — that would silently cancel the caller's ViewModel job ("honest over
 * silent"). Instead the engine converts it to this typed, non-cancellation signal, which
 * [com.calypsan.listenup.client.core.error.ErrorMapper]-adjacent boundary code
 * ([catchingRpcResult]) folds into a typed [com.calypsan.listenup.api.result.AppResult.Failure]
 * (an outcome-unknown transport failure) the caller can surface honestly.
 *
 * Not a `CancellationException` on purpose: it must survive the `catch (CancellationException)`
 * re-raise at the boundary and become a value instead.
 */
internal class RpcOutcomeUnknownException(
    cause: Throwable,
) : Exception("RPC frame was sent but its outcome is unknown — the response was lost.", cause)
