package com.calypsan.listenup.server.db.sqldelight

import com.calypsan.listenup.server.io.deleteRecursively
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import kotlin.random.Random
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

/**
 * Pins the one connection setting that used to differ between the JVM server and the shipped
 * native binary: foreign-key enforcement.
 *
 * The native driver has always passed `foreignKeyConstraints = true`; the JVM driver used to leave
 * it off. That made referential integrity a property of the platform rather than of the schema — an
 * insert-ordering bug could pass every JVM test and fail only inside the distroless image an
 * operator actually runs. This spec lives in `commonTest` deliberately: it executes on the JVM lane
 * AND the linuxX64 lane, so the two drivers can never silently disagree again.
 *
 * The assertion is behavioural rather than a `PRAGMA foreign_keys` read: it plants a real
 * parent/child pair and proves the orphan INSERT is refused, which is the guarantee callers
 * actually depend on.
 */
class ForeignKeyParityTest :
    FunSpec({

        test("the production driver enforces foreign keys on every platform") {
            val dir = Path(SystemTemporaryDirectory, "fkparity-${Random.nextLong().toString(16)}")
            SystemFileSystem.createDirectories(dir)
            val driver = DriverFactory().createDriver(Path(dir, "fk.db").toString())

            try {
                driver.execute(null, "CREATE TABLE parent (id TEXT NOT NULL PRIMARY KEY)", 0)
                driver.execute(
                    null,
                    "CREATE TABLE child (id TEXT NOT NULL PRIMARY KEY, " +
                        "parent_id TEXT NOT NULL REFERENCES parent(id))",
                    0,
                )

                shouldThrowAny {
                    driver.execute(null, "INSERT INTO child (id, parent_id) VALUES ('c1', 'absent')", 0)
                }
            } finally {
                driver.close()
                deleteRecursively(dir)
            }
        }
    })
