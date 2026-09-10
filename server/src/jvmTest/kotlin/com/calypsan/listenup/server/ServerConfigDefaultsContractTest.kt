package com.calypsan.listenup.server

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pins the JVM runtime's `application.conf` to the shared [SERVER_CONFIG_DEFAULTS] so the two config
 * sources can never drift — in either direction. The native runtime builds its config from the list,
 * the JVM runtime reads the HOCON file, and this test fails the build if a default in one is changed
 * without the other, or if `application.conf` documents an env override the list never declares
 * (which would make the documented knob silently dead on the shipped native binary).
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

        test("every LISTENUP_ env override in application.conf is declared in SERVER_CONFIG_DEFAULTS") {
            val confText =
                checkNotNull(ServerConfigDefaultsContractTest::class.java.getResource("/application.conf")) {
                    "application.conf missing from the test classpath"
                }.readText()
            val documented =
                ENV_OVERRIDE
                    .findAll(confText)
                    .map { it.groupValues[1] }
                    .toSet()
            val declared = SERVER_CONFIG_DEFAULTS.mapNotNull { it.envVar }.toSet()

            val undeclared = documented - declared - INTENTIONALLY_JVM_ONLY
            withClue(
                "env overrides documented in application.conf that nothing reads on native " +
                    "(add to SERVER_CONFIG_DEFAULTS, or exempt with a reason): $undeclared",
            ) {
                undeclared shouldBe emptySet()
            }
        }
    })

/** A HOCON optional-substitution override, e.g. `${?LISTENUP_SERVER_NAME}`; group 1 is the env var. */
private val ENV_OVERRIDE = Regex("""\$\{\?(LISTENUP_[A-Z0-9_]+)}""")

/**
 * Env overrides that legitimately appear in `application.conf` without a [SERVER_CONFIG_DEFAULTS]
 * entry. Every exemption carries its reason; a new one without a reason is a review failure.
 */
private val INTENTIONALLY_JVM_ONLY =
    setOf(
        // web.root is deliberately absent on native — the native image ships no web bundle.
        // Pinned by WebRootResolutionTest("no web.root property means no web client").
        "LISTENUP_WEB_ROOT",
        // Push relay config is read config-key-then-env directly (ApplicationConfig.kt
        // resolvePushRelayUrl / resolvePushSenderToken), so the env override works on native
        // without a SERVER_CONFIG_DEFAULTS entry.
        "LISTENUP_PUSH_RELAY_URL",
        "LISTENUP_PUSH_SENDER_TOKEN",
    )
