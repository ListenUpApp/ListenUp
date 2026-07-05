package com.calypsan.listenup.server.testing

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe

/**
 * The independent, executable specification of the **book-level access model** — the single
 * invariant the #680/#730 regression violated: a book is visible to a member IFF the pure-union
 * rule reaches it.
 *
 * **Why this file exists (read before touching an expectation here).** #680/#730 shipped and went
 * uncaught for ~two weeks because the tests pinned the *mechanism* (junction-table state) rather
 * than the *invariant* (user-visible access). Mechanism tests get legitimately rewritten during a
 * refactor; the invariant broke silently underneath them. This oracle re-derives the visibility
 * rule **independently of production** — [naiveVisible] does NOT call [BookAccessPolicy] and does
 * NOT share its SQL — so a guard built on it (G1's access-invariant matrix, and the G2 property
 * test) can never drift in lockstep with the code it guards. Changing an expectation here is an
 * **access-model change** and requires explicit sign-off — it is not a mechanical test fixup.
 *
 * The rule, stated as prose and encoded once in [naiveVisible]: *a live book is visible to a member
 * iff at least one live `collection_books` junction links it to a live collection the member owns or
 * holds a live USER grant on.* No "uncollected is public" branch, no global-access branch — public
 * visibility is structural (the `ALL_BOOKS` substrate collection + the member's default grant), so
 * the union rule alone reaches exactly the intended set.
 *
 * **Scope of what this oracle catches — do not over-trust it.** It guards *production visibility-rule
 * drift* (e.g. a reintroduced "uncollected is public" branch in [BookAccessPolicy]): [naiveVisible]
 * and `canAccess` would then disagree, and the guard fires. It does **not**, by itself, catch the
 * #680 *membership-maintenance* bug (a mutation that forgets `reconcileSystemMembership`), because
 * both sides read the same junction state and would agree "visible." That specific regression is
 * caught by the explicit `canAccess(...).shouldBeFalse()` + `pullSince` assertions in
 * `AccessInvariantMatrixTest` (row I1) and by `CollectionMembershipRoutingRule` — so those must not be
 * deleted on the assumption the oracle covers them.
 */
object AccessModelOracle {
    /**
     * Independently re-derives whether member [userId] can see [bookId] under the pure-union rule,
     * querying the raw schema over [driver] — deliberately **not** via [BookAccessPolicy] and
     * deliberately a different SQL shape (an inner `JOIN` chain, not the policy's `EXISTS`
     * subquery) so the two definitions cannot drift together. If production's rule regresses, this
     * still encodes the intended answer and the guard fires.
     *
     * Member semantics only: an admin/root sees every live book unconditionally, which is not the
     * union invariant this oracle guards.
     */
    suspend fun naiveVisible(
        db: ListenUpDatabase,
        driver: SqlDriver,
        userId: String,
        bookId: String,
    ): Boolean =
        suspendTransaction(db) {
            val sql =
                """
                SELECT 1
                FROM books b
                JOIN collection_books cb ON cb.book_id = b.id AND cb.deleted_at IS NULL
                JOIN collections c ON c.id = cb.collection_id AND c.deleted_at IS NULL
                WHERE b.id = ? AND b.deleted_at IS NULL
                  AND (
                    c.owner_id = ?
                    OR EXISTS (
                      SELECT 1 FROM collection_grants g
                      WHERE g.collection_id = c.id AND g.principal_type = 'USER'
                        AND g.principal_id = ? AND g.deleted_at IS NULL
                    )
                  )
                LIMIT 1
                """.trimIndent()
            driver
                .executeQuery(
                    identifier = null,
                    sql = sql,
                    mapper = { cursor -> QueryResult.Value(cursor.next().value) },
                    parameters = 3,
                    binders = {
                        bindString(0, bookId)
                        bindString(1, userId)
                        bindString(2, userId)
                    },
                ).value
        }

    /**
     * Asserts the load-bearing access invariant for [member]: for every book in [bookIds] the
     * production [BookAccessPolicy.canAccess] verdict agrees **exactly** with the independent
     * [naiveVisible] oracle. Weakening any guard that routes through this helper means editing a
     * line that reads as an access-control decision (`canAccess … shouldBe true/false`), not a
     * mechanism tweak — that is the whole point.
     *
     * Member semantics ([UserRole.MEMBER] by default); the oracle does not model admin god-view.
     */
    suspend fun assertAccessInvariants(
        policy: BookAccessPolicy,
        db: ListenUpDatabase,
        driver: SqlDriver,
        member: String,
        bookIds: List<String>,
        role: UserRole = UserRole.MEMBER,
    ) {
        for (bookId in bookIds) {
            val expected = naiveVisible(db, driver, member, bookId)
            val actual = policy.canAccess(member, role, bookId)
            withClue(
                "access-model invariant violated for member=$member book=$bookId " +
                    "— oracle(naiveVisible)=$expected but BookAccessPolicy.canAccess=$actual",
            ) {
                actual shouldBe expected
            }
        }
    }
}
