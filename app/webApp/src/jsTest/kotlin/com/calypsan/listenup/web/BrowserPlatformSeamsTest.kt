package com.calypsan.listenup.web

import com.calypsan.listenup.client.data.local.images.BrowserImageStorage
import com.calypsan.listenup.client.data.repository.BrowserNetworkMonitor
import com.calypsan.listenup.client.device.DeviceContextProvider
import com.calypsan.listenup.client.device.DeviceType
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.BrowserSecureStorage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.browser.localStorage
import kotlin.random.Random

/**
 * The browser implementations of the platform seams the shared Koin graph needs. Each spec
 * exercises the real browser API underneath — real localStorage, the real navigator — because
 * these classes exist precisely to bridge to those APIs.
 */
class BrowserPlatformSeamsTest :
    FunSpec({

        test("secure storage round-trips through localStorage") {
            val storage = BrowserSecureStorage(namespace = "test-${Random.nextInt(0, Int.MAX_VALUE)}")

            storage.save("token", "abc123")
            storage.read("token") shouldBe "abc123"

            storage.delete("token")
            storage.read("token") shouldBe null
        }

        test("secure storage persists beyond the instance, in localStorage itself") {
            val namespace = "test-persist-${Random.nextInt(0, Int.MAX_VALUE)}"
            BrowserSecureStorage(namespace).save("token", "abc123")

            // A fresh instance over the same namespace must see the value — the store is the
            // browser's, not the object's.
            BrowserSecureStorage(namespace).read("token") shouldBe "abc123"
        }

        test("clear removes this app's keys and nothing else") {
            // localStorage is origin-shared; clear() must not be localStorage.clear().
            val namespace = "test-clear-${Random.nextInt(0, Int.MAX_VALUE)}"
            val storage = BrowserSecureStorage(namespace)
            localStorage.setItem("unrelated-key", "keep-me")

            storage.save("a", "1")
            storage.save("b", "2")
            storage.clear()

            storage.read("a") shouldBe null
            storage.read("b") shouldBe null
            localStorage.getItem("unrelated-key") shouldBe "keep-me"
            localStorage.removeItem("unrelated-key")
        }

        test("the network monitor reflects the browser's online state") {
            val monitor = BrowserNetworkMonitor()

            // Headless Chromium in the test harness is online; the flow must agree with the
            // snapshot rather than defaulting independently.
            monitor.isOnline() shouldBe true
            monitor.isOnlineFlow.value shouldBe monitor.isOnline()
            monitor.isOnUnmeteredNetworkFlow.value shouldBe monitor.isOnline()
        }

        test("device detection classifies a fine-pointer browser as desktop") {
            DeviceContextProvider().detect().type shouldBe DeviceType.Desktop
        }

        test("image storage is coherent within a session") {
            val storage = BrowserImageStorage()
            val bookId = BookId("seam-test-book")

            storage.exists(bookId) shouldBe false
            storage.saveCover(bookId, ByteArray(3)).shouldBeInstanceOf<AppResult.Success<Unit>>()
            storage.exists(bookId) shouldBe true
            storage.listCoverBookIds() shouldBe setOf(bookId)

            storage.deleteCover(bookId).shouldBeInstanceOf<AppResult.Success<Unit>>()
            storage.exists(bookId) shouldBe false
        }
    })
