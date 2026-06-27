package com.calypsan.listenup.server.auth

/**
 * Applies Unicode NFC (canonical composition) to [raw] — the one step in [Email.normalize] that needs
 * a platform Unicode primitive. Composes a base letter followed by a combining diacritic into the
 * single precomposed character (`e` + `◌́` → `é`); leaves already-composed text and plain ASCII
 * untouched.
 *
 * JVM actual: full Unicode NFC via `java.text.Normalizer` (the historical behaviour — byte-identical,
 * so existing `email_normalized` values are unaffected). Native actual: a canonical-composition table
 * over the lowercase Latin diacritic range — the realistic email domain, since [Email.normalize]
 * lowercases before composing. The two agree on that domain (proven by `NfcNormalizeParityTest`,
 * which runs the same literals on both targets); native does not attempt the exotic multi-mark or
 * non-Latin compositions full NFC performs, which never appear in email addresses. Each self-hosted
 * server is internally consistent regardless.
 */
internal expect fun normalizeNfc(raw: String): String
