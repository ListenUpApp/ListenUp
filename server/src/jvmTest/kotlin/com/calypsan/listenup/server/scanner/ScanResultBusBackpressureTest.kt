package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.dto.scanner.ScanScope
import com.calypsan.listenup.server.di.EventBusQualifiers
import com.calypsan.listenup.server.di.scannerModule
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.dsl.koinApplication
import java.util.Collections

/**
 * Data-integrity regression coverage for finding A4: the scan-result bus silently discarded scan
 * results under load.
 *
 * The bus was a `MutableSharedFlow(replay = 0, extraBufferCapacity = 8, onBufferOverflow = DROP_OLDEST)`.
 * The producer (`Scanner.emit`) never suspends under DROP_OLDEST, so a burst of fast incremental scans
 * (a bulk reorg) queued faster than `BookPersister` — with its heavy DB + cover I/O — could drain.
 * Once more than 8 results were outstanding, the OLDEST was evicted: its Added/Modified never persisted,
 * its Removed tombstones never applied, its Completed never fired — all silently.
 *
 * The seam must be non-lossy: back-pressure the scanner (suspend the producer) rather than drop.
 * This test drives the REAL DI-provided bus with a deliberately slow consumer and asserts every
 * emitted result is received.
 */
class ScanResultBusBackpressureTest :
    FunSpec({

        test("the scan-result bus delivers every result even when the consumer is far slower than the producer") {
            val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val koin =
                koinApplication { modules(scannerModule(applicationScope = appScope)) }.koin
            val bus = koin.get<MutableSharedFlow<ScanResult>>(EventBusQualifiers.ScanResults)

            val emitted = 200 // far more than any bounded buffer holds — forces real back-pressure
            val received = Collections.synchronizedList(mutableListOf<String>())

            runBlocking {
                val consumer =
                    launch(Dispatchers.Default) {
                        bus.collect { result ->
                            // Slow consumer — mimics BookPersister's heavy per-result DB + cover I/O.
                            delay(5)
                            received += result.correlationId
                        }
                    }
                // Ensure the collector is subscribed before the producer starts emitting.
                bus.subscriptionCount.first { it > 0 }

                repeat(emitted) { i -> bus.emit(minimalResult("corr-$i")) }

                // Give the consumer a bounded window to drain. A non-lossy seam back-pressured the
                // producer, so all 50 are in flight and arrive; a lossy DROP_OLDEST seam stalls well
                // short of 50 and the size assertion below fails with the dropped count.
                val deadline = System.currentTimeMillis() + 5_000
                while (received.size < emitted && System.currentTimeMillis() < deadline) delay(10)

                consumer.cancel()
            }

            appScope.cancel()

            // Every emitted result was delivered — none silently dropped.
            received.size shouldBe emitted
            received.toSet() shouldBe (0 until emitted).map { "corr-$it" }.toSet()
        }
    })

private fun minimalResult(id: String): ScanResult =
    ScanResult(
        correlationId = id,
        rootPath = "/lib",
        books = emptyList(),
        changes = emptyList(),
        errors = emptyList(),
        durationMs = 0L,
        filesWalked = 0,
        filesSkipped = 0,
        scope = ScanScope.Full,
    )
