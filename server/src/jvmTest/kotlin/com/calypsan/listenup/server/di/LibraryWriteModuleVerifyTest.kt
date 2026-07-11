package com.calypsan.listenup.server.di

import com.calypsan.listenup.server.librarywrite.LibraryWriteBroker
import com.calypsan.listenup.server.librarywrite.SelfWriteRegistry
import com.calypsan.listenup.server.librarywrite.WriteJournal
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import java.nio.file.Files
import kotlin.time.Clock
import kotlinx.io.files.Path as IoPath
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class LibraryWriteModuleVerifyTest :
    FunSpec({
        test("libraryWriteModule resolves the registry, journal, and broker") {
            val homeDir = Files.createTempDirectory("listenup-librarywrite-verify-")
            try {
                val app =
                    koinApplication {
                        modules(
                            module { single<Clock> { Clock.System } },
                            libraryWriteModule(IoPath(homeDir.toString())),
                        )
                    }
                app.koin.get<SelfWriteRegistry>().shouldNotBeNull()
                app.koin.get<WriteJournal>().shouldNotBeNull()
                app.koin.get<LibraryWriteBroker>().shouldNotBeNull()
                app.close()
            } finally {
                homeDir.toFile().deleteRecursively()
            }
        }
    })
