package com.calypsan.listenup.web

import com.calypsan.listenup.api.dto.auth.DEVICE_FIELD_MAX
import com.calypsan.listenup.client.diagnostics.probeBookDetailPresentation
import com.calypsan.listenup.client.diagnostics.probeClientGraph
import com.calypsan.listenup.client.diagnostics.probeDeviceInfo
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.browser.window
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

        test("DeviceInfoProvider resolves in the browser graph and builds a valid DeviceInfo") {
            val dbName = "listenup-deviceinfo-${Random.nextInt(0, Int.MAX_VALUE)}.db"

            val probe = probeDeviceInfo(createSqliteWorker(), dbName)

            // The regression: DeviceInfoProvider had NO binding on Kotlin/JS at all, which
            // white-screened sign-in, setup, and registration alike (NoDefinitionFoundException).
            probe.providerResolved shouldBe true
            probe.platform shouldBe "Web"
            probe.clientName shouldBe "ListenUp Web"

            // DeviceInfo's own init block throws if any field exceeds DEVICE_FIELD_MAX, so simply
            // reaching this point already proves no field is too long. Assert it explicitly too,
            // rather than trusting that a `.take(DEVICE_FIELD_MAX)` call was made correctly.
            val deviceName = probe.deviceName.shouldNotBeNull()
            (deviceName.length <= DEVICE_FIELD_MAX) shouldBe true
            deviceName shouldBe window.navigator.userAgent.take(DEVICE_FIELD_MAX)
        }
    })
