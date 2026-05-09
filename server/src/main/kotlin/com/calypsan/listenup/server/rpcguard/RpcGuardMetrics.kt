package com.calypsan.listenup.server.rpcguard

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

/**
 * Counter for exceptions that escaped a guarded `@Rpc` service method.
 *
 * Metric: `rpc_uncaught_exceptions_total{service, method, cause}`. Self-hosters
 * scrape this as a Prometheus counter; consistent with the project's
 * Micrometer + Prometheus convention.
 */
internal class RpcGuardMetrics(
    private val registry: MeterRegistry,
) {
    fun recordEscape(
        service: String,
        method: String,
        cause: String,
    ) {
        registry
            .counter(
                METRIC_NAME,
                "service",
                service,
                "method",
                method,
                "cause",
                cause,
            ).increment()
    }

    companion object {
        private const val METRIC_NAME = "rpc_uncaught_exceptions_total"

        /**
         * Process-wide registry used by generated `<Service>Guarded` classes when
         * no registry is injected at construction. Replaced at server startup
         * with the application's actual [MeterRegistry] via [installGlobal].
         */
        @Volatile
        var global: RpcGuardMetrics = RpcGuardMetrics(SimpleMeterRegistry())
            private set

        fun installGlobal(registry: MeterRegistry) {
            global = RpcGuardMetrics(registry)
        }
    }
}
