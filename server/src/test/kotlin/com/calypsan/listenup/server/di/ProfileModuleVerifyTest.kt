package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.ProfileService
import com.calypsan.listenup.server.auth.PasswordHasher
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class ProfileModuleVerifyTest :
    FunSpec({
        test("profileModule resolves ProfileService") {
            withInMemoryDatabase {
                val db = this
                val app =
                    koinApplication {
                        modules(
                            module {
                                single { db }
                                single { PasswordHasher() }
                            },
                            profileModule(),
                        )
                    }
                app.koin.get<ProfileService>().shouldNotBeNull()
                app.close()
            }
        }
    })
