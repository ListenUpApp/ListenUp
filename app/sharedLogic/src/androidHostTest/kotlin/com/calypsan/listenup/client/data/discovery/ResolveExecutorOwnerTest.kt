package com.calypsan.listenup.client.data.discovery

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tests for [ResolveExecutorOwner] — the executor NsdManager's API-34 resolution path delivers on.
 *
 * Two failures bracket it. Minting an executor per `onServiceFound` and never stopping one leaked a
 * live thread per discovered server. The fix for that shut the shared executor down in
 * `stopDiscovery` — and NsdManager, which still held it, then crashed the app delivering a late
 * callback. The executor must neither leak nor ever reject.
 */
class ResolveExecutorOwnerTest :
    FunSpec({

        test("repeat acquisition shares one executor") {
            val owner = ResolveExecutorOwner()
            (owner.acquire() === owner.acquire()) shouldBe true
        }

        test("a callback NsdManager delivers after discovery stops is run, not rejected") {
            // NsdManager keeps the executor it was given and posts onServiceUpdated /
            // onServiceInfoCallbackUnregistered on its own ConnectivityThread after stopDiscovery.
            // A rejection there is an uncaught exception on a system thread: the whole app dies.
            // Seen on a Pixel 11 Pro XL 34 times in four days, twice mid token-refresh.
            val owner = ResolveExecutorOwner()
            val executor = owner.acquire()
            // Discovery has stopped: nothing in the owner's API can retire the executor any more.

            val ran = CountDownLatch(1)
            executor.execute { ran.countDown() }

            ran.await(5, TimeUnit.SECONDS) shouldBe true
        }

        test("the resolve thread exits once idle, so no thread outlives discovery") {
            val owner = ResolveExecutorOwner(keepAlive = 50.milliseconds)
            val ran = CountDownLatch(1)
            owner.acquire().execute { ran.countDown() }
            ran.await(5, TimeUnit.SECONDS) shouldBe true

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (owner.liveThreads > 0 && System.nanoTime() < deadline) Thread.sleep(10)

            owner.liveThreads shouldBe 0
        }

        test("an idle executor still accepts work after its thread has exited") {
            val owner = ResolveExecutorOwner(keepAlive = 50.milliseconds)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            owner.acquire().execute {}
            while (owner.liveThreads > 0 && System.nanoTime() < deadline) Thread.sleep(10)

            val ran = CountDownLatch(1)
            owner.acquire().execute { ran.countDown() }

            ran.await(5, TimeUnit.SECONDS) shouldBe true
        }
    })
