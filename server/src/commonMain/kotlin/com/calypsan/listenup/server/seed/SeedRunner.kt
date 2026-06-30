package com.calypsan.listenup.server.seed

import com.calypsan.listenup.server.logging.loggerFor

private val logger = loggerFor<SeedRunner>()

/**
 * Runs the registered [DomainSeeder]s in ascending [DomainSeeder.order]. Each seeder
 * runs only when its domain is empty, so invoking the runner against an already-populated
 * database is a no-op — never a clobber. Invoked once at startup under the `demo` seed profile.
 */
class SeedRunner(
    private val seeders: List<DomainSeeder>,
) {
    suspend fun run() {
        seeders.sortedBy { it.order }.forEach { seeder ->
            if (seeder.isAlreadySeeded()) {
                logger.info { "seed: '${seeder.domainName}' already populated — skipping" }
            } else {
                logger.info { "seed: seeding '${seeder.domainName}'" }
                seeder.seed()
            }
        }
    }
}
