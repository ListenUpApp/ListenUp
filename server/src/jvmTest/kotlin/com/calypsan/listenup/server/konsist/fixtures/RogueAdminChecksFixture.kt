package com.calypsan.listenup.server.konsist.fixtures

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.UserRole.ADMIN
import com.calypsan.listenup.api.dto.auth.UserRole.ROOT
import com.calypsan.listenup.server.auth.isAdmin

/**
 * Deliberately violates [com.calypsan.listenup.server.konsist.OneAdminCheckRule], so the self-test can
 * prove the rule fires. Lives under `jvmTest`, so production scope never sees it.
 *
 * Four private admin checks, one per spelling — ROOT-first, ADMIN-first, a set, a `when` branch — and
 * one that defers to the shared `isAdmin()`, which passes.
 */
internal object RogueAdminChecksFixture {
    fun rootFirst(role: UserRole): Boolean = role == UserRole.ROOT || role == UserRole.ADMIN

    fun adminFirst(role: UserRole): Boolean = role == UserRole.ADMIN || role == UserRole.ROOT

    fun asSet(role: UserRole): Boolean = role in setOf(ROOT, ADMIN)

    fun asWhen(role: UserRole): Boolean =
        when (role) {
            ROOT, ADMIN -> true
            else -> false
        }

    fun shared(role: UserRole): Boolean = role.isAdmin()
}
