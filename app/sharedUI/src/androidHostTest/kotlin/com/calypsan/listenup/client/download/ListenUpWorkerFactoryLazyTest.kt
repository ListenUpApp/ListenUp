package com.calypsan.listenup.client.download

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldNotBe

/**
 * Verifies that constructing [ListenUpWorkerFactory] does not force any of its
 * [Lazy] dependencies.
 *
 * This pins the fix for the WorkManager init-ordering crash: before the fix,
 * constructing the factory at DI-resolution time would eagerly resolve the download graph
 * (BookTransfers → its scheduler → WorkManager.getInstance()), which threw
 * because WorkManager.initialize() had not yet been called.  After the fix, all
 * dependencies are [Lazy]; none is accessed until [WorkerFactory.createWorker]
 * runs at job-dispatch time.
 */
class ListenUpWorkerFactoryLazyTest :
    FunSpec({
        // Each dep is a [Lazy] whose [Lazy.value] throws.  If constructing the factory
        // forces any dep, this test fails with that error.  If all deps are truly lazy,
        // construction succeeds and [shouldNotBe] confirms we got a valid instance.
        test("constructing ListenUpWorkerFactory does not force any dependency") {
            val factory =
                ListenUpWorkerFactory(
                    bookTransfers = lazy { error("bookTransfers forced at construction") },
                    adminRepository = lazy { error("adminRepository forced at construction") },
                )
            // If we reach here, no dep was forced — the factory was safely constructed.
            factory shouldNotBe null
        }

        // Sanity-check that the lazy sentinels above actually do throw when accessed,
        // so we know a regression would not silently pass.
        test("lazy sentinel throws when its value is accessed") {
            val sentinel: Lazy<Unit> = lazy { error("sentinel forced") }
            shouldThrow<IllegalStateException> { sentinel.value }
        }
    })
