package com.calypsan.listenup.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import java.nio.file.Files

/**
 * Pins [resolveDatabaseUrl] — the production default that maps the SQLite DB into
 * `$LISTENUP_HOME/listenup.db`, with `LISTENUP_HOME` defaulting to `~/ListenUp`.
 *
 * The resolver takes the configured `database.jdbcUrl` and an explicit
 * `listenupHome` override so it stays pure and testable (no env reads here).
 */
class DataHomeTest :
    FunSpec({

        test("blank jdbcUrl resolves to listenup.db inside the given home") {
            val home = Files.createTempDirectory("lu-home-").resolve("ListenUp")

            val url = resolveDatabaseUrl(configuredUrl = "", listenupHome = home)

            url shouldStartWith "jdbc:sqlite:"
            url shouldContain home.resolve("listenup.db").toString()
        }

        test("blank jdbcUrl creates the home directory if absent") {
            val home = Files.createTempDirectory("lu-home-").resolve("ListenUp")
            Files.exists(home) shouldBe false

            resolveDatabaseUrl(configuredUrl = "", listenupHome = home)

            Files.isDirectory(home) shouldBe true
        }

        test("an explicit configured jdbcUrl wins and is returned verbatim") {
            val home = Files.createTempDirectory("lu-home-").resolve("ListenUp")

            val url =
                resolveDatabaseUrl(
                    configuredUrl = "jdbc:sqlite:/tmp/explicit.db",
                    listenupHome = home,
                )

            url shouldBe "jdbc:sqlite:/tmp/explicit.db"
            // The home dir is NOT created when an explicit URL is supplied.
            Files.exists(home) shouldBe false
        }

        test("defaultListenupHome is a ListenUp subfolder of the user home") {
            val home = defaultListenupHome(userHome = "/home/alice")
            home.toString() shouldBe "/home/alice/ListenUp"
        }
    })
