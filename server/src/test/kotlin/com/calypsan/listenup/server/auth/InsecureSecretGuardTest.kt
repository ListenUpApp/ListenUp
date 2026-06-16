package com.calypsan.listenup.server.auth

import com.calypsan.listenup.server.di.INSECURE_DEFAULT_JWT_SECRET
import com.calypsan.listenup.server.di.INSECURE_DEFAULT_REFRESH_PEPPER
import com.calypsan.listenup.server.di.rejectInsecureSecrets
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.ktor.server.config.MapApplicationConfig

/**
 * Verifies that [rejectInsecureSecrets] refuses to proceed when either the JWT
 * signing secret or the refresh-token pepper equals the committed test default
 * in `application.conf`, and that the opt-in and custom values are accepted.
 */
class InsecureSecretGuardTest :
    FunSpec({

        fun config(
            jwtSecret: String = "x".repeat(32),
            refreshPepper: String = "x".repeat(32),
            allowInsecureSecrets: String? = null,
        ): MapApplicationConfig =
            MapApplicationConfig(
                "jwt.secret" to jwtSecret,
                "auth.refreshPepper" to refreshPepper,
            ).apply {
                if (allowInsecureSecrets != null) put("auth.allowInsecureSecrets", allowInsecureSecrets)
            }

        test("default JWT secret without opt-in throws and names LISTENUP_JWT_SECRET") {
            val ex =
                shouldThrow<IllegalStateException> {
                    config(jwtSecret = INSECURE_DEFAULT_JWT_SECRET).rejectInsecureSecrets()
                }
            ex.message shouldContain "LISTENUP_JWT_SECRET"
        }

        test("default refresh pepper without opt-in throws and names LISTENUP_REFRESH_PEPPER") {
            val ex =
                shouldThrow<IllegalStateException> {
                    config(refreshPepper = INSECURE_DEFAULT_REFRESH_PEPPER).rejectInsecureSecrets()
                }
            ex.message shouldContain "LISTENUP_REFRESH_PEPPER"
        }

        test("default secret and pepper are permitted when allowInsecureSecrets = true") {
            shouldNotThrowAny {
                config(
                    jwtSecret = INSECURE_DEFAULT_JWT_SECRET,
                    refreshPepper = INSECURE_DEFAULT_REFRESH_PEPPER,
                    allowInsecureSecrets = "true",
                ).rejectInsecureSecrets()
            }
        }

        test("non-default secret and pepper are permitted regardless of opt-in") {
            shouldNotThrowAny {
                config(
                    jwtSecret = "a-genuinely-unique-production-secret-value",
                    refreshPepper = "a-genuinely-unique-production-pepper-value",
                ).rejectInsecureSecrets()
            }
        }
    })
