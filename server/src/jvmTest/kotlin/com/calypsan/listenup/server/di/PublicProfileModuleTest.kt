package com.calypsan.listenup.server.di

import com.calypsan.listenup.server.services.PublicProfileMaintainer
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class PublicProfileModuleTest :
    FunSpec({
        test("publicProfileModule resolves PublicProfileRepository and PublicProfileMaintainer") {
            withInMemoryDatabase {
                val db = this
                val app =
                    koinApplication {
                        modules(
                            module {
                                single { db }
                                single { SyncRegistry() }
                                single { ChangeBus() }
                            },
                            publicProfileModule(),
                        )
                    }
                try {
                    app.koin.get<PublicProfileRepository>().shouldNotBeNull()
                    app.koin.get<PublicProfileMaintainer>().shouldNotBeNull()
                } finally {
                    app.close()
                }
            }
        }
    })
