package com.calypsan.listenup.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Pins every migration main has shipped to the checksum it shipped with.
 *
 * [MigrationRunner] refuses to boot when an applied migration's checksum changes, so editing a
 * shipped file — even a comment — strands every deployed server. This fails the build first.
 */
class ShippedMigrationChecksumTest :
    FunSpec({

        test("no shipped migration has changed since it reached main") {
            val manifest =
                checkNotNull(javaClass.getResource("/db/shipped-migration-checksums.txt")) {
                    "db/shipped-migration-checksums.txt is missing from the test resources"
                }.readText()
                    .lineSequence()
                    .map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .map { line -> line.split(' ').let { (version, checksum) -> version.toInt() to checksum } }
                    .toList()

            val bundled = MigrationCatalog.all.associate { it.version to it }
            val drifted =
                manifest.mapNotNull { (version, shippedChecksum) ->
                    val migration = bundled[version]
                    when {
                        migration == null -> {
                            "V$version shipped on main but is no longer bundled."
                        }

                        migration.checksum != shippedChecksum -> {
                            "V${version}__${migration.name}.sql changed after it shipped " +
                                "($shippedChecksum → ${migration.checksum}). Deployed servers refuse to boot " +
                                "on that. Restore the file byte-for-byte and change the schema in a new migration."
                        }

                        else -> {
                            null
                        }
                    }
                }

            drifted.shouldBeEmpty()
        }
    })
