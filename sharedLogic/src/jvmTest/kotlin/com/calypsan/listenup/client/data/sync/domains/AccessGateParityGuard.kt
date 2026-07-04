package com.calypsan.listenup.client.data.sync.domains

/**
 * Pure logic for the server↔client access-gate parity guard (Plan §6, Guard 1).
 *
 * The critical invariant: **every domain the server access-filters *per row* on
 * catch-up/digest must have a client [AccessGate] that prunes the pruned-away rows.**
 * If the server narrows a member's visible set (a revoke, an unshare, a private
 * release) but the client domain has no gate, the pruned rows never leave Room —
 * permanent digest drift plus a stale-visible privacy row. That is the exact class
 * of bug an ungated `activities` domain would (and did) reintroduce.
 *
 * This object is deliberately free of any Konsist / runtime dependency so the parsing
 * and the set comparison can be exercised directly on synthetic input by a fixture
 * test — the rule's own proof that it fires on a violation.
 */
internal object AccessGateParityGuard {
    /**
     * The whole-domain, role-gated domains that are **intentionally exempt** from the
     * client-[AccessGate] obligation: their rows are hidden wholesale from non-admins, so
     * a member holds no rows for them and has nothing to prune. Adding a new role-gate to
     * `accessFilterFor` forces a conscious edit here (the parity spec asserts this set),
     * rather than silently slipping a per-row gate past the guard.
     */
    val ROLE_GATED_EXEMPT: Set<String> = setOf("library_folders", "admin_user_roster")

    /**
     * The domains that `accessFilterFor` gates **per row** — the branches that route through
     * `policy()` to a member-scoped id set. These are the domains that oblige a client
     * [AccessGate]. Parsed from [syncRoutesSource] so a new server branch is picked up with
     * no edit here.
     *
     * Robust to the constant indirection: the `when` branches on `BOOKS_DOMAIN` etc., so the
     * constant → wire-literal map (`const val BOOKS_DOMAIN = "books"`) is resolved first.
     */
    fun parsePerRowGatedDomains(syncRoutesSource: String): Set<String> =
        parseGatedBranches(syncRoutesSource, perRow = true)

    /** The whole-domain role-gated branches (`if (isAdmin(role)) …`, no `policy()`) — the exempt set. */
    fun parseRoleGatedDomains(syncRoutesSource: String): Set<String> =
        parseGatedBranches(syncRoutesSource, perRow = false)

    /**
     * The offenders: server per-row-gated domains with **no** client [AccessGate]. Empty is the
     * only healthy value. A non-empty result is the critical bug, caught at build time.
     */
    fun offenders(
        serverPerRowGated: Set<String>,
        clientAccessGated: Set<String>,
    ): Set<String> = serverPerRowGated - clientAccessGated

    private fun parseGatedBranches(
        source: String,
        perRow: Boolean,
    ): Set<String> {
        val constants = constantLiterals(source)
        val whenBody = extractWhenBody(source) ?: return emptySet()
        return BRANCH_REGEX
            .findAll(whenBody)
            .mapNotNull { match ->
                val constName = match.groupValues[1]
                val body = match.groupValues[2]
                if (constName == "else") return@mapNotNull null
                val gatesPerRow = body.contains("policy()")
                if (gatesPerRow != perRow) return@mapNotNull null
                constants[constName]
            }.toSet()
    }

    /** Maps `const val NAME = "literal"` declarations to their string value. */
    private fun constantLiterals(source: String): Map<String, String> =
        CONST_REGEX
            .findAll(source)
            .associate { it.groupValues[1] to it.groupValues[2] }

    /** The body between the braces of `when (domainName) { … }`, or null if absent. */
    private fun extractWhenBody(source: String): String? {
        val marker = source.indexOf("when (domainName)")
        if (marker < 0) return null
        val open = source.indexOf('{', marker)
        if (open < 0) return null
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, i)
                }
            }
        }
        return null
    }

    private val CONST_REGEX = Regex("""const\s+val\s+(\w+)\s*=\s*"([^"]+)"""")

    // One `CONST -> <rhs up to end of line>` branch. The rhs capture stops at newline, which is
    // enough to detect `policy()` vs the `if (isAdmin(role))` role-gate shape.
    private val BRANCH_REGEX = Regex("""(?m)^\s*(\w+)\s*->\s*(.*)$""")
}
