package com.calypsan.listenup.server.services

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry

/**
 * Micrometer counters for scanner-driven book ingest.
 *
 * Self-hosted, single-instance: there's no Prometheus scrape today. The counter
 * exists so a persistent ingest failure is *countable* in logs/diagnostics, not
 * because a metrics pipeline consumes it. A `SimpleMeterRegistry` is the
 * expected backing registry.
 */
class BookPersisterMetrics(
    registry: MeterRegistry,
) {
    /** Incremented once per book that failed to persist during scanner ingest. */
    val bookPersistFailures: Counter =
        Counter
            .builder("listenup.books.persist.failures")
            .description("Per-book persistence failures during scanner-driven ingest")
            .register(registry)
}
