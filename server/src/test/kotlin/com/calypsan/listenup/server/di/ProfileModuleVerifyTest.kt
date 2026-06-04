package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.ProfileService
import com.calypsan.listenup.server.auth.PasswordHasher
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.testing.noOpPublicProfileMaintainer
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import java.nio.file.Files
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class ProfileModuleVerifyTest :
    FunSpec({
        test("profileModule resolves ProfileService and ImageStore") {
            val avatarsDir = Files.createTempDirectory("listenup-profile-verify-")
            try {
                withInMemoryDatabase {
                    val db = this
                    val app =
                        koinApplication {
                            modules(
                                module {
                                    single { db }
                                    single { PasswordHasher() }
                                    single { db.noOpPublicProfileMaintainer() }
                                },
                                profileModule(avatarsDir),
                            )
                        }
                    app.koin.get<ProfileService>().shouldNotBeNull()
                    app.koin.get<ImageStore>().shouldNotBeNull()
                    app.close()
                }
            } finally {
                avatarsDir.toFile().deleteRecursively()
            }
        }
    })
