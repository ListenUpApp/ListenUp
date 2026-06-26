package com.calypsan.listenup.server.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class MaintenanceStateTest :
    FunSpec({

        test("enter is single-flight: second enter returns false until exit") {
            val s = MaintenanceState()
            s.enter() shouldBe true
            s.enter() shouldBe false
            s.exit()
            s.enter() shouldBe true
        }

        test("drain returns true once in-flight returns to zero") {
            runTest {
                val s = MaintenanceState()
                s.beginRequest()
                s.beginRequest()
                s.endRequest()
                s.endRequest()
                s.drain(timeoutMs = 1000, stepMs = 1) shouldBe true
            }
        }

        test("isActive reflects the gate") {
            val s = MaintenanceState()
            s.isActive shouldBe false
            s.enter()
            s.isActive shouldBe true
            s.exit()
            s.isActive shouldBe false
        }
    })
