package com.calypsan.listenup.server.seed

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.test.runTest

class SeedRunnerTest :
    FunSpec({
        /** A fake seeder that records calls and reports a controllable "already seeded" state. */
        class FakeSeeder(
            override val domainName: String,
            override val order: Int,
            private var seeded: Boolean,
            val log: MutableList<String>,
        ) : DomainSeeder {
            override suspend fun isAlreadySeeded(): Boolean = seeded

            override suspend fun seed() {
                log += domainName
                seeded = true
            }
        }

        test("runs every not-yet-seeded seeder, in ascending order") {
            val log = mutableListOf<String>()
            val runner =
                SeedRunner(
                    listOf(
                        FakeSeeder("b", order = 20, seeded = false, log = log),
                        FakeSeeder("a", order = 10, seeded = false, log = log),
                    ),
                )

            runTest { runner.run() }

            log shouldContainExactly listOf("a", "b")
        }

        test("skips a seeder whose domain is already populated") {
            val log = mutableListOf<String>()
            val runner =
                SeedRunner(
                    listOf(
                        FakeSeeder("fresh", order = 10, seeded = false, log = log),
                        FakeSeeder("populated", order = 20, seeded = true, log = log),
                    ),
                )

            runTest { runner.run() }

            log shouldContainExactly listOf("fresh")
        }

        test("running twice is a no-op the second time") {
            val log = mutableListOf<String>()
            val runner = SeedRunner(listOf(FakeSeeder("x", order = 10, seeded = false, log = log)))

            runTest {
                runner.run()
                runner.run()
            }

            log shouldContainExactly listOf("x")
        }
    })
