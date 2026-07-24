package com.calypsan.listenup.client.data.sync.domains

/**
 * The conscious-edit set for the server↔client access-gate parity guard (Plan §6, Guard 1).
 *
 * The parity invariant is now a **data comparison** (see `AccessGateParitySpec`): the server's
 * runtime catalog of per-row access-gated domains
 * ([com.calypsan.listenup.server.sync.perRowAccessGatedSyncDomains]) must equal the set of client
 * [MirroredDomain]s that declare an `accessGate`. No source parsing — a per-row-gated server
 * domain with no matching client gate (or the reverse) fails the equality directly.
 *
 * This object holds only the intentional exemptions: the whole-domain, role-gated domains whose
 * rows are hidden wholesale from non-admins. A member holds no rows for them and has nothing to
 * prune, so they carry no client `AccessGate`. Adding a new role gate to the server's
 * `ACCESS_FILTERS` forces a conscious edit here (the parity spec asserts this set equals the
 * server's [com.calypsan.listenup.server.sync.roleGatedSyncDomains]).
 */
internal object AccessGateParityGuard {
    /**
     * The whole-domain, role-gated domains that are **intentionally exempt** from the
     * client-[com.calypsan.listenup.client.data.sync.domains.AccessGate] obligation.
     */
    val ROLE_GATED_EXEMPT: Set<String> = setOf("library_folders", "admin_user_roster")
}
