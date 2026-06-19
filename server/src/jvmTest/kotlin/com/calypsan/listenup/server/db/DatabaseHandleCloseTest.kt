package com.calypsan.listenup.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class DatabaseHandleCloseTest :
    FunSpec({
        test("close() shuts the pool, isPoolClosed reports it, and close is idempotent") {
            val tmp =
                Files
                    .createTempFile("listenup-dbhandle-", ".db")
                    .toFile()
                    .apply { deleteOnExit() }
            val handle = DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:${tmp.absolutePath}"))

            handle.isPoolClosed() shouldBe false
            handle.close()
            handle.isPoolClosed() shouldBe true
            handle.close() // second close must not throw
            handle.isPoolClosed() shouldBe true
        }
    })
