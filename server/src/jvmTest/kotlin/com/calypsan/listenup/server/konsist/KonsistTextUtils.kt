package com.calypsan.listenup.server.konsist

/**
 * Strips line (`// …`) and block (`/* … */`) comments from Kotlin source text so a Konsist rule
 * that text-matches a forbidden token (a banned disk-write call, a symbol-derived logger) can't
 * false-fail on a commented-out occurrence or a KDoc mention.
 *
 * Shared by [SidecarParsersAreReadOnly] and [NoSymbolDerivedLoggerNamesRule] — one copy so the two
 * guards can't drift apart.
 */
internal fun stripComments(source: String): String =
    source
        .replace(Regex("""//[^\n]*"""), "")
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
