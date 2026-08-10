package com.calypsan.listenup.server.testing

import com.calypsan.listenup.server.auth.PepperedHasher
import com.calypsan.listenup.server.auth.ResetCodeGenerator
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.services.PasswordResetService
import com.calypsan.listenup.server.services.SessionRevoker
import kotlin.time.Clock

/** Any 32+ byte value works — the reset service is never asked to verify against production data. */
private val TEST_RESET_PEPPER = "reset-test-pepper-".repeat(2).toByteArray()

/**
 * A real [PasswordResetService] over [db], for tests that construct [com.calypsan.listenup.server.auth.AuthServiceImpl]
 * / [com.calypsan.listenup.server.api.AdminUserServiceImpl] directly and just need the now-mandatory
 * parameter satisfied. [sessions] defaults to a no-op revoker — pass a real [SessionRevoker] only
 * for a test that actually drives [PasswordResetService.complete] and needs to observe the revocation.
 */
fun testPasswordResetService(
    db: ListenUpDatabase,
    clock: Clock,
    sessions: SessionRevoker = SessionRevoker { },
): PasswordResetService =
    PasswordResetService(
        db = db,
        hasher = PepperedHasher(TEST_RESET_PEPPER),
        codes = ResetCodeGenerator(),
        clock = clock,
        sessions = sessions,
    )
