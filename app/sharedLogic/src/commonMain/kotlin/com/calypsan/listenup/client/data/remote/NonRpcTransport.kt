package com.calypsan.listenup.client.data.remote

/**
 * The category of raw-HTTP transport a [NonRpcTransport]-tagged surface uses, and — more to the
 * point — *why it cannot ride the [RpcChannel] seam*. Every first-party call is RPC-by-default;
 * a data-layer class that reaches for the Ktor client instead must name which of these deliberate,
 * architecturally-sanctioned exceptions applies.
 *
 * Exactly one reason survives, and that is the point: raw bytes are the only thing that genuinely
 * cannot be a JSON-RPC frame. A `THIRD_PARTY_REST` case once sat here for endpoints kept on REST by
 * product choice; every one of them turned out to be a surface that *should* have been RPC, so both
 * they and the reason are gone. If a new surface doesn't carry bytes, it belongs on RPC — adding a
 * reason to make it fit would be arguing with the architecture rather than following it.
 */
internal enum class NonRpcReason {
    /**
     * A raw byte stream — image/avatar bytes, a `.listenup.zip` backup, an `.audiobookshelf` import
     * archive, a book document — flowing as `multipart/form-data` or a streamed response body. These
     * can't be marshalled into a JSON-RPC frame (kotlinx.rpc serializes structured values, not
     * arbitrary binary), so they ride a dedicated REST endpoint via the Ktor client.
     */
    BINARY_TRANSFER,
}

/**
 * Marks a data-layer class as a **declared, reviewed exception** to the project's RPC-by-default
 * mandate: it performs raw HTTP through [ApiClientFactory] instead of dispatching through the
 * [RpcChannel] seam, and the [reason] records why that is legitimate.
 *
 * The point of the seam is that every robustness capability the RPC engine gains — bounded timeouts,
 * 401-heal, single-flight reconnect, outcome-unknown typing — reaches every service for free, and
 * "reach past the engine" is a compile error, not a review comment. Raw HTTP forgoes all of that, so
 * it must never be the *silent* default. Applying this annotation is a decision: it says a human
 * looked at the surface, confirmed it genuinely can't (or by product choice doesn't) ride RPC, and
 * named the bucket it falls in.
 *
 * The [RawHttpTransportIsDeclaredRule] Konsist rule enforces the mandate structurally: any class
 * under `data/remote/` or `data/repository/` that calls a Ktor client accessor and is NOT part of the
 * fixed RPC-infrastructure allowlist MUST carry this annotation, or the build fails. That makes the
 * capstone promise physical — "if something's not on RPC and we can't justify why, we move it over."
 *
 * A class may be *mixed* (some calls ride the channel, some are raw REST — e.g. backup lists/creates
 * over RPC but streams the archive over REST): it still carries exactly one annotation, for its
 * raw-HTTP portion, and its KDoc notes the split.
 *
 * SOURCE retention: this is a compile-time architectural marker Konsist reads from source; it carries
 * no runtime weight and never crosses the wire.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
internal annotation class NonRpcTransport(
    val reason: NonRpcReason,
    val justification: String = "",
)
