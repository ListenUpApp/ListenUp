package com.calypsan.listenup.server.auth

import java.text.Normalizer

/**
 * Email normalization + minimal format check. Self-hosted threat model: we are not RFC-5321-perfect;
 * we just enforce "looks like an email" and a stable normalization for storage and lookup.
 */
object Email {
    /** Trim, lowercase, and apply Unicode NFC. The result is what goes in `email_normalized`. */
    fun normalize(raw: String): String = Normalizer.normalize(raw.trim().lowercase(), Normalizer.Form.NFC)

    /** Minimal `@` with at least one char on each side. */
    fun isLikelyEmail(raw: String): Boolean {
        val at = raw.indexOf('@')
        return at > 0 && at < raw.length - 1
    }
}
