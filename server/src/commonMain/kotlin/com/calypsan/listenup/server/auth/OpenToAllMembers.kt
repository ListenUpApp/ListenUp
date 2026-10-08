package com.calypsan.listenup.server.auth

/**
 * Declares that a mutating RPC is deliberately open to every signed-in member (or, on the public mount,
 * to anyone), so `MutatingRpcsAreGatedRule` does not demand a permission gate. [reason] says why, in a
 * sentence a reviewer can check — almost always "it acts only on the caller's own data".
 *
 * Source-only: it exists for the rule and for the reader, and never reaches a binary.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
internal annotation class OpenToAllMembers(
    val reason: String,
)
