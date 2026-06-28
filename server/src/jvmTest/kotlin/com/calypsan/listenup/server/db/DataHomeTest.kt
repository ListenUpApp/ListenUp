package com.calypsan.listenup.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
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
            val home = Path(Files.createTempDirectory("lu-home-").resolve("ListenUp").toString())

            val url = resolveDatabaseUrl(configuredUrl = "", listenupHome = home)

            url shouldStartWith "jdbc:sqlite:"
            url shouldContain Path(home, "listenup.db").toString()
        }

        test("blank jdbcUrl creates the home directory if absent") {
            val home = Path(Files.createTempDirectory("lu-home-").resolve("ListenUp").toString())
            SystemFileSystem.exists(home) shouldBe false

            resolveDatabaseUrl(configuredUrl = "", listenupHome = home)

            (SystemFileSystem.metadataOrNull(home)?.isDirectory == true) shouldBe true
        }

        test("an explicit configured jdbcUrl wins and is returned verbatim") {
            val home = Path(Files.createTempDirectory("lu-home-").resolve("ListenUp").toString())

            val url =
                resolveDatabaseUrl(
                    configuredUrl = "jdbc:sqlite:/tmp/explicit.db",
                    listenupHome = home,
                )

            url shouldBe "jdbc:sqlite:/tmp/explicit.db"
            // The home dir is NOT created when an explicit URL is supplied.
            SystemFileSystem.exists(home) shouldBe false
        }

        test("defaultListenupHome is a ListenUp subfolder of the user home") {
            val home = defaultListenupHome(userHome = "/home/alice")
            home.toString() shouldBe "/home/alice/ListenUp"
        }

        test("resolveListenupHome prefers LISTENUP_HOME env, else userHome/ListenUp") {
            resolveListenupHome(envHome = "/custom/home", userHome = "/Users/x") shouldBe Path("/custom/home")
            resolveListenupHome(envHome = null, userHome = "/Users/x") shouldBe Path("/Users/x", "ListenUp")
            resolveListenupHome(envHome = "  ", userHome = "/Users/x") shouldBe Path("/Users/x", "ListenUp")
        }

        test("resolveListenupHome (config-aware): listenup.home config wins, then env, then default — ONE home for all data (#703)") {
            // config wins over env + default — this is what the DB/secrets previously ignored
            resolveListenupHome(configuredHome = "/data/lu", envHome = "/env/lu", userHome = "/Users/x") shouldBe
                Path("/data/lu")
            // blank/absent config falls through to env
            resolveListenupHome(configuredHome = "  ", envHome = "/env/lu", userHome = "/Users/x") shouldBe
                Path("/env/lu")
            resolveListenupHome(configuredHome = null, envHome = "/env/lu", userHome = "/Users/x") shouldBe
                Path("/env/lu")
            // no config, no env → userHome/ListenUp
            resolveListenupHome(configuredHome = null, envHome = null, userHome = "/Users/x") shouldBe
                Path("/Users/x", "ListenUp")
        }
    })
