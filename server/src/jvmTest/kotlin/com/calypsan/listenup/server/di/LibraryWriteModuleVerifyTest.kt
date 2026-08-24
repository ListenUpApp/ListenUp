package com.calypsan.listenup.server.di

import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.librarywrite.LibraryRootProvider
import com.calypsan.listenup.server.librarywrite.LibraryWriteBroker
import com.calypsan.listenup.server.librarywrite.SelfWriteRegistry
import com.calypsan.listenup.server.librarywrite.WriteJournal
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.time.Clock
import kotlinx.io.files.Path as IoPath
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class LibraryWriteModuleVerifyTest :
    FunSpec({
        test("libraryWriteModule resolves the registry, journal, root provider, and broker") {
            val homeDir = Files.createTempDirectory("listenup-librarywrite-verify-")
            try {
                // A real migrated database, not a stub: the broker now depends on a
                // LibraryRootProvider backed by `library_folders`, and overriding that binding
                // would leave the module's own wiring unverified — which is this test's whole job.
                val handle =
                    DatabaseFactory.init(
                        DatabaseConfig(jdbcUrl = "jdbc:sqlite:${homeDir.resolve("listenup.db")}"),
                    )
                val app =
                    koinApplication {
                        modules(
                            module {
                                single<Clock> { Clock.System }
                                single { ListenUpDatabase(handle.sqlDriver) }
                            },
                            libraryWriteModule(IoPath(homeDir.toString())),
                        )
                    }
                app.koin.get<SelfWriteRegistry>().shouldNotBeNull()
                app.koin.get<WriteJournal>().shouldNotBeNull()
                app.koin.get<LibraryWriteBroker>().shouldNotBeNull()

                val roots = app.koin.get<LibraryRootProvider>().shouldNotBeNull()
                // Exercises the query, not just the binding — a malformed `selectLiveRootPaths`
                // would resolve fine and only fail the first time a write was attempted.
                // Empty is also the correct answer here: no folders configured means nowhere
                // legitimate to write, and the broker fails closed on that.
                roots.roots() shouldBe emptyList()

                app.close()
            } finally {
                homeDir.toFile().deleteRecursively()
            }
        }
    })
