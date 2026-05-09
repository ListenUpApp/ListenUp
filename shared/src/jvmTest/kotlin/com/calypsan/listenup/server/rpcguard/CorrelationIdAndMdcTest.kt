package com.calypsan.listenup.server.rpcguard

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.slf4j.MDC

class CorrelationIdAndMdcTest :
    FunSpec({

        test("currentCorrelationId reads callId from MDCContext") {
            runTest {
                withContext(MDCContext(mapOf("callId" to "abc-123"))) {
                    currentCorrelationId() shouldBe "abc-123"
                }
            }
        }

        test("currentCorrelationId returns null when MDCContext absent") {
            runTest {
                currentCorrelationId() shouldBe null
            }
        }

        test("newCorrelationId returns a UUID-shaped string") {
            val id = newCorrelationId()
            id.length shouldBe 36
            id[8] shouldBe '-'
        }

        test("withMdc augments MDC for the duration of the block and restores after") {
            runTest {
                withContext(MDCContext(mapOf("callId" to "outer"))) {
                    withMdc("service" to "Foo", "method" to "bar", "correlationId" to "outer") {
                        MDC.get("callId") shouldBe "outer"
                        MDC.get("service") shouldBe "Foo"
                        MDC.get("method") shouldBe "bar"
                        MDC.get("correlationId") shouldBe "outer"
                    }
                    MDC.get("service") shouldBe null
                }
            }
        }
    })
