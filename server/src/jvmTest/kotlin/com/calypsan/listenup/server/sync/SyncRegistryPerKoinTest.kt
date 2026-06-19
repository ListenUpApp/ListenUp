package com.calypsan.listenup.server.sync

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.di.syncModule
import com.calypsan.listenup.server.testing.asSqlDatabase
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.assertions.throwables.shouldThrow
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
                                module {
                                    single<Database> { dbA }
                                    single<ListenUpDatabase> { dbA.asSqlDatabase() }
                                },
                                syncModule(),
                            )
                        }
                    val koinB =
                        koinApplication {
                            modules(
                                module {
                                    single<Database> { dbB }
                                    single<ListenUpDatabase> { dbB.asSqlDatabase() }
                                },
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
                            module {
                                single<Database> { db }
                                single<ListenUpDatabase> { db.asSqlDatabase() }
                            },
                            syncModule(),
                        )
                    }

                try {
                    val registry: SyncRegistry = koin.koin.get()
                    // syncModule wires the tag domains (TagRepository "tags",
                    // BookTagRepository "book_tags"), the mood domains (MoodRepository
                    // "moods", BookMoodRepository "book_moods"), and the collection domains
                    // (CollectionRepository "collections", CollectionBookRepository
                    // "collection_books", CollectionShareRepository "collection_shares").
                    registry.knownDomains().toSet() shouldBe
                        setOf(
                            "tags",
                            "book_tags",
                            "moods",
                            "book_moods",
                            "collections",
                            "collection_books",
                            "collection_shares",
                        )
                } finally {
                    koin.close()
                }
            }
        }

        test("registering two repositories with the same domainName throws IllegalStateException") {
            withInMemoryDatabase {
                val db = this
                val registry = SyncRegistry()
                TagRepository(db.asSqlDatabase(), ChangeBus(), registry) // first registration succeeds
                shouldThrow<IllegalStateException> {
                    TagRepository(db.asSqlDatabase(), ChangeBus(), registry) // duplicate domainName → throws
                }
            }
        }
    })
