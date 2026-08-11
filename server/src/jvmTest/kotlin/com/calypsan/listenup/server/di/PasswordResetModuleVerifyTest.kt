package com.calypsan.listenup.server.di

import com.calypsan.listenup.server.auth.RootResetToken
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.ktor.server.config.MapApplicationConfig
import kotlin.time.Clock
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Pins [RootResetToken] as a Koin `single`, not a `factory` — see the ⛔ comment at its binding
 * in [passwordResetModule]. A `factory` would mint a fresh token per injection: the value printed
 * at startup would never match the one [com.calypsan.listenup.server.auth.AuthServiceImpl] checks
 * on every subsequent call, and the hatch would be permanently — and silently — dead, since that
 * failure is indistinguishable from an ordinary wrong-token attempt.
 */
class PasswordResetModuleVerifyTest :
    FunSpec({
        test("RootResetToken resolves to the same instance on every injection") {
            val config =
                MapApplicationConfig(
                    "auth.refreshPepper" to "x".repeat(32),
                    "jwt.secret" to "x".repeat(32),
                )
            val app =
                koinApplication {
                    modules(
                        module { single<Clock> { Clock.System } },
                        passwordResetModule(config),
                    )
                }

            app.koin.get<RootResetToken>() shouldBeSameInstanceAs app.koin.get<RootResetToken>()

            app.close()
        }
    })
