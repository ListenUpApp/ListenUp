package com.calypsan.listenup.server.rpcguard

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

class RpcGuardMetricsTest :
    FunSpec({

        test("recordEscape increments rpc_uncaught_exceptions_total with the right tags") {
            val registry = SimpleMeterRegistry()
            val metrics = RpcGuardMetrics(registry)

            metrics.recordEscape("AuthServicePublic", "login", "NullPointerException")
            metrics.recordEscape("AuthServicePublic", "login", "NullPointerException")
            metrics.recordEscape("ScannerService", "scanFull", "SQLException")

            registry
                .counter(
                    "rpc_uncaught_exceptions_total",
                    "service",
                    "AuthServicePublic",
                    "method",
                    "login",
                    "cause",
                    "NullPointerException",
                ).count() shouldBe 2.0

            registry
                .counter(
                    "rpc_uncaught_exceptions_total",
                    "service",
                    "ScannerService",
                    "method",
                    "scanFull",
                    "cause",
                    "SQLException",
                ).count() shouldBe 1.0
        }
    })
