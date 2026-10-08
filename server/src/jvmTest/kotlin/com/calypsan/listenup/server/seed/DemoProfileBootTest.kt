package com.calypsan.listenup.server.seed

import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.testing.testApplication
import kotlin.time.Duration.Companion.seconds
import com.calypsan.listenup.server.testing.publicAuthService

class DemoProfileBootTest :
    FunSpec({
        test("with seed.profile=demo the server boots and the demo user can log in") {
            testApplication {
                useIsolatedTestConfig(seedProfile = "demo")
                application { module() }

                // Generous window: demo seeding runs async post-boot and hashes the demo
                // password with Argon2id (memory-hard by design), which a loaded shared CI
                // runner can take several seconds to finish. `eventually` returns on the first
                // success, so the wide bound costs nothing on a fast machine — 5s flaked on CI
                // (login kept returning InvalidCredentials until the seed landed).
                eventually(30.seconds) {
                    val response =
                        publicAuthService()
                            .login(
                                LoginRequest(
                                    email = UserDomainSeeder.DEMO_EMAIL,
                                    password = UserDomainSeeder.DEMO_PASSWORD,
                                ),
                            )
                    response.shouldBeInstanceOf<AppResult.Success<AuthSession>>()
                }
            }
        }
    })
