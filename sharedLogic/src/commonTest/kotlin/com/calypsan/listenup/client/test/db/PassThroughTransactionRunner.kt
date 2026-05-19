package com.calypsan.listenup.client.test.db

import com.calypsan.listenup.client.data.local.db.TransactionRunner
import dev.mokkery.answering.calls
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock

/**
 * Returns a Mokkery-mocked [TransactionRunner] whose [TransactionRunner.atomically]
 * pass-through-invokes the lambda. Use in unit tests of repository methods that wrap
 * DAO writes in `transactionRunner.atomically { ... }` — verifies that the block runs
 * without requiring a real database.
 *
 * Pair with `verifySuspend(VerifyMode.exactly(1)) { runner.atomically(any<suspend () -> Any>()) }`
 * in the test body to assert the transaction wrapper was used.
 */
fun passThroughTransactionRunner(): TransactionRunner =
    mock<TransactionRunner> {
        everySuspend { atomically(any<suspend () -> Any>()) } calls { args ->
            @Suppress("UNCHECKED_CAST")
            val block = args.arg(0) as suspend () -> Any
            block()
        }
    }
