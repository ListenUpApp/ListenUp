package com.calypsan.listenup.server

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pins the JVM runtime's `application.conf` to the shared [SERVER_CONFIG_DEFAULTS] so the two config
 * sources can never drift: the native runtime builds its config from the list, the JVM runtime reads
 * the HOCON file, and this test fails the build if a default in one is changed without the other.
 *
 * Substitutions are resolved with the process environment OFF, so each `${?ENV}` override falls back to
 * its in-file default — making the comparison deterministic regardless of the CI environment.
 */
class ServerConfigDefaultsContractTest :
    FunSpec({
        val applicationConf =
            ConfigFactory
                .parseResources("application.conf")
                .resolve(ConfigResolveOptions.defaults().setUseSystemEnvironment(false))

        test("application.conf default matches ServerConfigDefaults for every key") {
            SERVER_CONFIG_DEFAULTS.forEach { entry ->
                withClue("config key '${entry.key}'") {
                    applicationConf.getValue(entry.key).unwrapped().toString() shouldBe entry.default
                }
            }
        }
    })
