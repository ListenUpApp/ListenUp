package com.calypsan.listenup.server.auth

/**
 * Declares that a read RPC deliberately returns the same answer to every signed-in caller (or, on the
 * public mount, to anyone), so `ReadRpcsNameTheirCallerRule` does not demand a caller check. [reason]
 * says why, in a sentence a reviewer can check.
 *
 * Source-only: it exists for the rule and for the reader, and never reaches a binary.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
internal annotation class OpenRead(
    val reason: String,
)
