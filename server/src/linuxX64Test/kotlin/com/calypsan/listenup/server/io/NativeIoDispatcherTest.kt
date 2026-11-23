package com.calypsan.listenup.server.io

import com.calypsan.listenup.server.db.sqldelight.sqlIoDispatcher
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * Verifies on Kotlin/Native Linux that [fileIoDispatcher] and [sqlIoDispatcher] use the real
 * [Dispatchers.IO] elastic pool rather than sharing [Dispatchers.Default] with Ktor and CPU work.
 */
class NativeIoDispatcherTest :
    FunSpec({
        test("fileIoDispatcher and sqlIoDispatcher resolve to Dispatchers.IO on native") {
            fileIoDispatcher shouldBeSameInstanceAs Dispatchers.IO
            fileIoDispatcher shouldNotBeSameInstanceAs Dispatchers.Default
            sqlIoDispatcher shouldBeSameInstanceAs Dispatchers.IO
            sqlIoDispatcher shouldNotBeSameInstanceAs Dispatchers.Default
        }

        test("withContext runs work on fileIoDispatcher and sqlIoDispatcher") {
            val fileResult = withContext(fileIoDispatcher) { "file-ok" }
            val sqlResult = withContext(sqlIoDispatcher) { "sql-ok" }

            fileResult shouldBe "file-ok"
            sqlResult shouldBe "sql-ok"
        }
    })
