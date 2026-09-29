package com.calypsan.listenup.web.di

import com.calypsan.listenup.web.createSqliteWorker
import com.calypsan.listenup.web.startWebKoin
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.w3c.dom.Worker

/** Stands in for a graph singleton that has to exist before anyone asks for it. */
private class EagerProbe(
    onBuilt: () -> Unit,
) {
    init {
        onBuilt()
    }
}

/**
 * The browser boots its graph with every `createdAtStart` singleton built, as every other platform
 * does. Koin's JS `startKoin` skips that step, so in a browser the sync domain registrar never ran
 * before the first sync — which then walked an empty registry, pulled nothing, and left a freshly
 * signed-in library empty.
 */
class WebKoinStartTest :
    FunSpec({
        test("a createdAtStart singleton is built at boot, before anyone asks for it") {
            var built = false
            startWebKoin(
                module { single<Worker> { createSqliteWorker() } },
                module { single(createdAtStart = true) { EagerProbe(onBuilt = { built = true }) } },
            )
            try {
                built shouldBe true
            } finally {
                stopKoin()
            }
        }
    })
