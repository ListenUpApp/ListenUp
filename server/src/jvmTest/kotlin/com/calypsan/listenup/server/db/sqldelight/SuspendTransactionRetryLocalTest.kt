package com.calypsan.listenup.server.db.sqldelight

import com.calypsan.listenup.server.testing.migratedTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.sql.SQLException
import kotlinx.coroutines.withContext

/**
 * Regression coverage for [suspendTransaction]'s [TransactionLocal] mirror: the mirror must be
 * re-established on EVERY retry attempt, not just once before the retry loop starts. The former
 * "set once" shape loses the mirror (or leaks a stale value from another use of the same thread)
 * whenever a retry resumes on a different worker thread after `delay(...)` — see plan 002.
 */
class SuspendTransactionRetryLocalTest :
    FunSpec({
        test("a retried attempt re-establishes the mirror, even if it was corrupted between attempts") {
            val testDb = migratedTestDatabase()
            var callCount = 0
            val observedPerAttempt = mutableListOf<Any?>()

            val result =
                withContext(TransactionLocal("desired-value")) {
                    suspendTransaction(testDb.db) {
                        callCount++
                        observedPerAttempt += currentTransactionLocal()
                        if (callCount == 1) {
                            // Simulate what a genuine thread hop would produce: whatever thread the
                            // NEXT attempt resumes on holds a value that belongs to someone else
                            // (another coroutine's extras, or nothing at all) — never "desired-value".
                            setTransactionLocal("stale-value-from-another-coroutine")
                            throw SQLException("snapshot superseded", "SQLITE_BUSY_SNAPSHOT", 517)
                        }
                        "ok"
                    }
                }

            result shouldBe "ok"
            observedPerAttempt shouldBe listOf("desired-value", "desired-value")
        }

        test("single successful attempt sees the context value, and it does not leak into an unrelated call") {
            val testDb = migratedTestDatabase()
            var observedWithContext: Any? = "not-set"
            var observedWithoutContext: Any? = "not-set"

            withContext(TransactionLocal("only-value")) {
                suspendTransaction(testDb.db) {
                    observedWithContext = currentTransactionLocal()
                    "ok"
                }
            }
            suspendTransaction(testDb.db) {
                observedWithoutContext = currentTransactionLocal()
                "ok2"
            }

            observedWithContext shouldBe "only-value"
            observedWithoutContext shouldBe null
        }

        test("nested-scope transactions each see their own context value, and the outer value returns after") {
            val testDb = migratedTestDatabase()
            val observed = mutableListOf<Any?>()

            withContext(TransactionLocal("A")) {
                suspendTransaction(testDb.db) {
                    observed += currentTransactionLocal()
                    "outer1"
                }
                withContext(TransactionLocal("B")) {
                    suspendTransaction(testDb.db) {
                        observed += currentTransactionLocal()
                        "inner"
                    }
                }
                suspendTransaction(testDb.db) {
                    observed += currentTransactionLocal()
                    "outer2"
                }
            }

            observed shouldBe listOf("A", "B", "A")
        }
    })
