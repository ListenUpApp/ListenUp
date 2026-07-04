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
        classifyAccessFilter(syncRoutesSource).perRowGated

    /** The whole-domain role-gated branches (`if (isAdmin(role)) …`, no `policy()`) — the exempt set. */
    fun parseRoleGatedDomains(syncRoutesSource: String): Set<String> =
        classifyAccessFilter(syncRoutesSource).roleGated

    /**
     * A **total** classification of every arm of `accessFilterFor`'s `when (domainName)`. Each arm
     * lands in exactly one bucket: [perRowGated] (routes through `policy()`), [roleGated]
     * (whole-domain `if (isAdmin(role)) …`), the `else`/default (counted, held nowhere), or
     * [unparsedArms] — the loud-fail bucket for any arm the guard could not place.
     *
     * The point of tracking [unparsedArms] and [totalArms]: a future branch written in a shape the
     * parser doesn't understand — `"activities" ->`, `SyncDomains.ACTIVITIES.name ->`, `A, B ->`,
     * `in setOf(...) ->` — must **break the build**, never silently vanish from both the per-row set
     * and the exempt set (the exact silent-bypass that would ship a per-row-filtered domain with no
     * client [AccessGate]). The parity spec asserts `classifiedArms == totalArms`.
     */
    data class AccessFilterClassification(
        val perRowGated: Set<String>,
        val roleGated: Set<String>,
        val unparsedArms: List<String>,
        val totalArms: Int,
    ) {
        /** Arms the guard placed with confidence (per-row + role-gated + the `else` default). */
        val classifiedArms: Int get() = totalArms - unparsedArms.size
    }

    /**
     * Classify every `when (domainName)` arm. The only arms treated as understood are `else`, a bare
     * constant whose value is known via [constantLiterals] routed through `policy()`, and a bare
     * constant routed through `isAdmin`. Everything else is [AccessFilterClassification.unparsedArms].
     */
    fun classifyAccessFilter(source: String): AccessFilterClassification {
        val constants = constantLiterals(source)
        val whenBody =
            extractWhenBody(source)
                ?: return AccessFilterClassification(emptySet(), emptySet(), emptyList(), totalArms = 0)

        val perRow = mutableSetOf<String>()
        val roleGated = mutableSetOf<String>()
        val unparsed = mutableListOf<String>()
        val arms = whenArms(whenBody)
        for (arm in arms) {
            if (arm.condition == "else") continue // the default arm — understood, held nowhere.
            val literal = constants[arm.condition] // only a single bare constant resolves; else → null.
            val gatesPerRow = arm.body.contains("policy()")
            val gatesByRole = arm.body.contains("isAdmin")
            when {
                literal != null && gatesPerRow && !gatesByRole -> perRow += literal
                literal != null && gatesByRole && !gatesPerRow -> roleGated += literal
                else -> unparsed += arm.raw
            }
        }
        return AccessFilterClassification(perRow, roleGated, unparsed, totalArms = arms.size)
    }

    /**
     * The offenders: server per-row-gated domains with **no** client [AccessGate]. Empty is the
     * only healthy value. A non-empty result is the critical bug, caught at build time.
     */
    fun offenders(
        serverPerRowGated: Set<String>,
        clientAccessGated: Set<String>,
    ): Set<String> = serverPerRowGated - clientAccessGated

    /** One arm of the `when`, split at its first top-level `->`. [raw] is kept for loud-fail messages. */
    private data class Arm(val raw: String, val condition: String, val body: String)

    /**
     * Split a `when` body into its arms. One arm per non-blank, non-comment line that carries a
     * `->` — which matches `accessFilterFor`'s one-arm-per-line shape. The condition is everything
     * before the first `->`; the rest is the body. Multi-condition (`A, B ->`) stays a single arm
     * with a comma-bearing condition that no single-constant lookup resolves — so it fails loud.
     */
    private fun whenArms(whenBody: String): List<Arm> =
        whenBody
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && it.contains("->") }
            .map { line ->
                val arrow = line.indexOf("->")
                Arm(
                    raw = line,
                    condition = line.substring(0, arrow).trim(),
                    body = line.substring(arrow + 2).trim(),
                )
            }.toList()

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
                '{' -> {
                    depth++
                }

                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, i)
                }
            }
        }
        return null
    }

    private val CONST_REGEX = Regex("""const\s+val\s+(\w+)\s*=\s*"([^"]+)"""")
}
