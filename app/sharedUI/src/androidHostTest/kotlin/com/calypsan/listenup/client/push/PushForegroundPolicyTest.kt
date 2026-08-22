package com.calypsan.listenup.client.push

import com.calypsan.listenup.api.push.PushPayload
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PushForegroundPolicyTest :
    FunSpec({

        val test = PushPayload.TestNotification(sentAtMs = 1L)
        val other = PushPayload.RegistrationApproval(userId = "u1")

        test("a test notification renders even in the foreground — the case that was broken") {
            PushForegroundPolicy.shouldRender(test, appInForeground = true) shouldBe true
        }

        test("a test notification renders in the background too") {
            PushForegroundPolicy.shouldRender(test, appInForeground = false) shouldBe true
        }

        test("an ordinary payload is suppressed in the foreground") {
            PushForegroundPolicy.shouldRender(other, appInForeground = true) shouldBe false
        }

        test("an ordinary payload renders in the background") {
            PushForegroundPolicy.shouldRender(other, appInForeground = false) shouldBe true
        }

        test("an undecodable payload follows the ordinary rule, not the exception") {
            PushForegroundPolicy.shouldRender(null, appInForeground = true) shouldBe false
            PushForegroundPolicy.shouldRender(null, appInForeground = false) shouldBe true
        }
    })
