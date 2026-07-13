package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.ProfileService
import com.calypsan.listenup.server.auth.Argon2Limiter
import com.calypsan.listenup.server.auth.PasswordHasher
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.testing.noOpPublicProfileMaintainer
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import java.nio.file.Files
import kotlin.time.Clock
import kotlinx.io.files.Path as IoPath
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class ProfileModuleVerifyTest :
    FunSpec({
        test("profileModule resolves ProfileService and ImageStore") {
            val avatarsDir = Files.createTempDirectory("listenup-profile-verify-")
            try {
                withSqlDatabase {
                    val app =
                        koinApplication {
                            modules(
                                module {
                                    single<ListenUpDatabase> { sql }
                                    single { Argon2Limiter(PasswordHasher()) }
                                    single { sql.noOpPublicProfileMaintainer() }
                                    single<Clock> { Clock.System }
                                },
                                profileModule(IoPath(avatarsDir.toString())),
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
