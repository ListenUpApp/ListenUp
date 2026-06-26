package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.client.share.ShareTarget
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DeepLinkManagerTest :
    FunSpec({

        test("pendingTarget starts null") {
            DeepLinkManager().pendingTarget.value shouldBe null
        }

        test("setPendingTarget then consumeTarget drives pendingTarget and hasPendingTarget") {
            val manager = DeepLinkManager()
            val target = ShareTarget.Book(bookId = BookId("book-1"), serverInstanceId = "inst-1", serverUrl = null)

            manager.pendingTarget.test {
                awaitItem() shouldBe null

                manager.setPendingTarget(target)
                awaitItem() shouldBe target
                manager.hasPendingTarget() shouldBe true

                manager.consumeTarget()
                awaitItem() shouldBe null
                manager.hasPendingTarget() shouldBe false
            }
        }
    })
