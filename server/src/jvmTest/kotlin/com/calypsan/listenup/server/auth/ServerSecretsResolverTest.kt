package com.calypsan.listenup.server.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Verifies [resolveSecret]'s precedence: an explicit non-blank env value is used
 * (and validated), otherwise the committed-default sentinel triggers store
 * generation while a test-injected non-default config value is passed through.
 */
class ServerSecretsResolverTest :
    FunSpec({

        val generated = "generated-secret-value-that-is-clearly-over-32-bytes"

        class FakeSecretStore : SecretStore {
            var calls = 0

            override fun getOrGenerate(key: String): String {
                calls++
                return generated
            }
        }

        test("env unset and config at committed default generates from the store") {
            val store = FakeSecretStore()
            val result =
                resolveSecret(
                    rawEnv = null,
                    configValue = INSECURE_DEFAULT_JWT_SECRET,
                    committedDefault = INSECURE_DEFAULT_JWT_SECRET,
                    store = store,
                    storeKey = "jwt.secret",
                    envVarName = "LISTENUP_JWT_SECRET",
                )
            result shouldBe generated
            store.calls shouldBe 1
        }

        test("env unset and config is a non-default value passes it through untouched") {
            val store = FakeSecretStore()
            val injected = "x".repeat(32)
            val result =
                resolveSecret(
                    rawEnv = null,
                    configValue = injected,
                    committedDefault = INSECURE_DEFAULT_JWT_SECRET,
                    store = store,
                    storeKey = "jwt.secret",
                    envVarName = "LISTENUP_JWT_SECRET",
                )
            result shouldBe injected
            store.calls shouldBe 0
        }

        test("a valid explicit env value is used without touching the store") {
            val store = FakeSecretStore()
            val explicit = "a-genuinely-unique-production-secret-value-32b+"
            val result =
                resolveSecret(
                    rawEnv = explicit,
                    configValue = explicit,
                    committedDefault = INSECURE_DEFAULT_JWT_SECRET,
                    store = store,
                    storeKey = "jwt.secret",
                    envVarName = "LISTENUP_JWT_SECRET",
                )
            result shouldBe explicit
            store.calls shouldBe 0
        }

        test("an explicit env value equal to the committed default throws naming the var") {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    resolveSecret(
                        rawEnv = INSECURE_DEFAULT_JWT_SECRET,
                        configValue = INSECURE_DEFAULT_JWT_SECRET,
                        committedDefault = INSECURE_DEFAULT_JWT_SECRET,
                        store = FakeSecretStore(),
                        storeKey = "jwt.secret",
                        envVarName = "LISTENUP_JWT_SECRET",
                    )
                }
            ex.message shouldContain "LISTENUP_JWT_SECRET"
        }

        test("an explicit env value shorter than 32 bytes throws naming the var") {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    resolveSecret(
                        rawEnv = "too-short",
                        configValue = "too-short",
                        committedDefault = INSECURE_DEFAULT_JWT_SECRET,
                        store = FakeSecretStore(),
                        storeKey = "jwt.secret",
                        envVarName = "LISTENUP_JWT_SECRET",
                    )
                }
            ex.message shouldContain "LISTENUP_JWT_SECRET"
        }

        test("the pepper sentinel is handled symmetrically") {
            val store = FakeSecretStore()
            val result =
                resolveSecret(
                    rawEnv = null,
                    configValue = INSECURE_DEFAULT_REFRESH_PEPPER,
                    committedDefault = INSECURE_DEFAULT_REFRESH_PEPPER,
                    store = store,
                    storeKey = "auth.refreshPepper",
                    envVarName = "LISTENUP_REFRESH_PEPPER",
                )
            result shouldBe generated
            store.calls shouldBe 1
        }

        test("a blank explicit env value is treated as unconfigured") {
            val store = FakeSecretStore()
            val result =
                resolveSecret(
                    rawEnv = "   ",
                    configValue = INSECURE_DEFAULT_JWT_SECRET,
                    committedDefault = INSECURE_DEFAULT_JWT_SECRET,
                    store = store,
                    storeKey = "jwt.secret",
                    envVarName = "LISTENUP_JWT_SECRET",
                )
            result shouldBe generated
            store.calls shouldBe 1
        }
    })
