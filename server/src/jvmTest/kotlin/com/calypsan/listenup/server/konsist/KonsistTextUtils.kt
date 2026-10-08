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

/**
 * An admin check that denies: `if (!….isAdmin()) return|throw …`, `if (….isAdmin() != true) return|throw …`,
 * or either guard opening a block that returns or throws. A bare `.isAdmin()` that only reads the role
 * denies nothing, so it is not a gate.
 *
 * Shared by [MutatingRpcsAreGatedRule] and [MutatingRoutesAreGatedRule] — one copy so the two gates
 * cannot disagree on what a denial looks like.
 */
internal val ADMIN_DENIAL_SHAPE =
    Regex(
        """if\s*\(\s*(!\s*[\w.?()]+\.isAdmin\(\)|[\w.?()]+\.isAdmin\(\)\s*!=\s*true)\s*\)\s*""" +
            """(return|throw|\{[^}]*\b(return|throw)\b)""",
    )
