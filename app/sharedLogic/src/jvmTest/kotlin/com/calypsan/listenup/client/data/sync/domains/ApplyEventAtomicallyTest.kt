package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.types.shouldBeInstanceOf

private val logger = KotlinLogging.logger {}

private class FakeTransactionRunner : TransactionRunner {
    override suspend fun <R> atomically(block: suspend () -> R): R = block()
}

class ApplyEventAtomicallyTest :
    FunSpec({
        val runner = FakeTransactionRunner()

        test("a deferred post-commit action that throws does not fail an otherwise-successful apply") {
            var blockRan = false
            val result =
                runner.applyEventAtomically("test", "e1", logger) {
                    blockRan = true
                    deferUntilCommit { throw IllegalStateException("best-effort cleanup failed") }
                }

            result.shouldBeInstanceOf<AppResult.Success<Unit>>()
            blockRan.shouldBeTrue()
        }

        test("block() throwing still fails the apply and never runs the deferred action") {
            var deferredRan = false
            val result =
                runner.applyEventAtomically("test", "e1", logger) {
                    deferUntilCommit { deferredRan = true }
                    error("write failed")
                }

            result.shouldBeInstanceOf<AppResult.Failure>()
            deferredRan.shouldBeFalse()
        }
    })
