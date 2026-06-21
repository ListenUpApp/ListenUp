package com.calypsan.listenup.server.db.sqldelight

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.sql.SQLException

class RetryableSqliteErrorTest :
    FunSpec({
        test("SQLITE_BUSY (5) is retryable") {
            SQLException("database is locked", "SQLITE_BUSY", 5).isRetryableSqliteError() shouldBe true
        }
        test("SQLITE_BUSY_SNAPSHOT (517) is retryable") {
            SQLException("snapshot superseded", "SQLITE_BUSY_SNAPSHOT", 517).isRetryableSqliteError() shouldBe true
        }
        test("a constraint violation (19) is NOT retryable") {
            SQLException("constraint failed", "SQLITE_CONSTRAINT", 19).isRetryableSqliteError() shouldBe false
        }
        test("a busy error wrapped in a cause chain is retryable") {
            RuntimeException("wrapper", SQLException("database is locked", "SQLITE_BUSY", 5))
                .isRetryableSqliteError() shouldBe true
        }
        test("a plain non-SQL exception is NOT retryable") {
            IllegalStateException("nope").isRetryableSqliteError() shouldBe false
        }
    })
