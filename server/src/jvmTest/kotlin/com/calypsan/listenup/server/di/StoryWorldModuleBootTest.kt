package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.EntityService
import com.calypsan.listenup.api.WorldEventService
import com.calypsan.listenup.server.api.EntityServiceImpl
import com.calypsan.listenup.server.api.WorldEventServiceImpl
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import org.koin.ktor.ext.inject

/**
 * Boots the real `Application.module()` and proves [storyWorldModule] is in the graph: the `entities`
 * domain registers at start, and [EntityService] resolves to the permission-gated [EntityServiceImpl].
 */
class StoryWorldModuleBootTest :
    FunSpec({
        test("the production graph registers the Story World domains and serves both services") {
            val libraryRoot = Files.createTempDirectory("listenup-storyworld-boot-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    startApplication()
                    val registry by application.inject<SyncRegistry>()
                    ("entities" in registry.knownDomains()) shouldBe true
                    val service by application.inject<EntityService>()
                    (service is EntityServiceImpl) shouldBe true
                    ("world_events" in registry.knownDomains()) shouldBe true
                    val events by application.inject<WorldEventService>()
                    (events is WorldEventServiceImpl) shouldBe true
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }
    })
