package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.client.domain.model.BookContributor

/**
 * Display label used for a contributor that carries no explicit role.
 * Kept lowercase so [pluralizeRole] capitalises it uniformly with server-supplied roles.
 */
const val GENERIC_CONTRIBUTOR_ROLE = "contributor"

/**
 * The most names the Book Detail hero shows inline before folding into a
 * "{lead}, N other …" summary that opens the full-cast overlay. One lead plus one peer reads
 * cleanly as "A & B"; beyond that the names crowd the hero, so we fold.
 */
const val HERO_CONTRIBUTOR_FOLD_LIMIT = 2

/**
 * One credit row in the Book Detail credits section: a role and every contributor that fills it.
 *
 * Same-role contributors are grouped into a single entry so a fourteen-narrator book renders one
 * "Narrators" row with the names comma-joined, not fourteen identical rows.
 *
 * @property roleLabel   Display-ready role label — capitalised and pluralised for [contributors]
 *   size (e.g. "Author", "Narrators").
 * @property contributors The contributors filling this role, in first-appearance order.
 */
data class CreditRoleGroup(
    val roleLabel: String,
    val contributors: List<BookContributor>,
)

/**
 * Group [contributors] by role for the credits section, preserving first-appearance order for both
 * roles and the contributors within each role.
 *
 * A contributor with multiple roles appears once under each of its roles. A contributor with no
 * roles is grouped under [GENERIC_CONTRIBUTOR_ROLE] so it is never silently dropped. Each group's
 * [CreditRoleGroup.roleLabel] is capitalised and pluralised via [pluralizeRole].
 */
fun groupContributorsByRole(contributors: List<BookContributor>): List<CreditRoleGroup> {
    // LinkedHashMap keeps first-appearance role order; the per-role list keeps contributor order.
    val byRole = LinkedHashMap<String, MutableList<BookContributor>>()
    for (contributor in contributors) {
        val roles = contributor.roles.ifEmpty { listOf(GENERIC_CONTRIBUTOR_ROLE) }
        for (role in roles) {
            byRole.getOrPut(role) { mutableListOf() }.add(contributor)
        }
    }
    return byRole.map { (role, members) ->
        CreditRoleGroup(roleLabel = pluralizeRole(role, members.size), contributors = members)
    }
}

/**
 * Produce a display-ready role label: capitalise the first letter, and append an "s" when [count]
 * is greater than one.
 *
 * Pluralisation is deliberately conservative — it only appends "s" to a single bare word that does
 * not already end in "s". Multi-word roles (e.g. "foreword by") and already-plural roles are left
 * unchanged, since blindly suffixing them ("foreword bys") would read worse than the singular.
 */
fun pluralizeRole(
    role: String,
    count: Int,
): String {
    val base = role.trim()
    val shouldPluralize =
        count > 1 && !base.contains(' ') && !base.endsWith("s", ignoreCase = true)
    val labelled = if (shouldPluralize) base + "s" else base
    return labelled.replaceFirstChar { it.uppercase() }
}
