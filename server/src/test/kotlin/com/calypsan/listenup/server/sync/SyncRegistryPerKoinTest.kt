package com.calypsan.listenup.server.sync

import com.calypsan.listenup.server.di.syncModule
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Pins the per-Koin scoping of [SyncRegistry] — two independent Koin containers
 * must yield independent registries, so parallel `withTestApplication { }` blocks
 * (and Books-A's cross-domain Tags+Books tests) can't trample each other's
 * registrations.
 *
 * Before this task, [SyncRoutes] was a process-wide `internal object` holding a
 * static `ConcurrentHashMap`. The second `register("tags", …)` in a JVM run
 * would silently overwrite the first — invisible under serial test execution,
 * flaky under `kotest.parallelism > 1`. This test would have caught it.
 */
class SyncRegistryPerKoinTest :
    FunSpec({

        test("two Koin containers each have their own SyncRegistry") {
            withInMemoryDatabase {
                val dbA: Database = this
                withInMemoryDatabase {
                    val dbB: Database = this

                    val koinA =
                        koinApplication {
                            modules(
                                module { single<Database> { dbA } },
                                syncModule(),
                            )
                        }
                    val koinB =
                        koinApplication {
                            modules(
                                module { single<Database> { dbB } },
                                syncModule(),
                            )
                        }

                    try {
                        val registryA = koinA.koin.get<SyncRegistry>()
                        val registryB = koinB.koin.get<SyncRegistry>()

                        registryA shouldNotBeSameInstanceAs registryB
                        registryA.lookup("tags") shouldNotBeSameInstanceAs registryB.lookup("tags")
                    } finally {
                        koinA.close()
                        koinB.close()
                    }
                }
            }
        }

        test("single Koin container has one SyncRegistry with all registered repositories") {
            withInMemoryDatabase {
                val db: Database = this
                val koin =
                    koinApplication {
                        modules(
                            module { single<Database> { db } },
                            syncModule(),
                        )
                    }

                try {
                    val registry: SyncRegistry = koin.koin.get()
                    registry.knownDomains() shouldBe listOf("tags")
                } finally {
                    koin.close()
                }
            }
        }
    })
