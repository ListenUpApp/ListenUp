package com.calypsan.listenup.web

import com.calypsan.listenup.client.diagnostics.probeBookDetailPresentation
import com.calypsan.listenup.client.diagnostics.probeClientGraph
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.random.Random

/**
 * THE client-graph-on-web proof: the real shared Koin graph — every module the native clients
 * boot — starts in a browser, resolves the real `BookRepository` through its full constructor
 * chain (DAOs, transactions, image storage, network monitor, RPC channel, sync handler), and
 * round-trips a book through the OPFS-backed store.
 *
 * This is the spec that retires "the browser Koin modules are empty" from the web plan.
 */
class ClientGraphProbeTest :
    FunSpec({
        test("the shared Koin graph boots and BookRepository round-trips a book") {
            val dbName = "listenup-graph-${Random.nextInt(0, Int.MAX_VALUE)}.db"

            val probe = probeClientGraph(createSqliteWorker(), dbName)

            probe.repositoryResolved shouldBe true
            probe.ingestedTitle shouldBe "Foundation"
        }

        test("the real BookDetailViewModel reaches Ready in a browser") {
            val dbName = "listenup-presentation-${Random.nextInt(0, Int.MAX_VALUE)}.db"

            val probe = probeBookDetailPresentation(createSqliteWorker(), dbName)

            probe.viewModelResolved shouldBe true
            probe.readyTitle shouldBe "Foundation"
        }
    })
