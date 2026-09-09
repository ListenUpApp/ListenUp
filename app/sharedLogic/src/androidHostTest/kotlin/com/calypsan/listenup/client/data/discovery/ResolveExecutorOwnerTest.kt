package com.calypsan.listenup.client.data.discovery

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldNotBeSameInstanceAs

/**
 * Tests for [ResolveExecutorOwner] — the lifecycle of the executor NsdManager's API-34
 * resolution path needs.
 *
 * The bug that motivates this: every `onServiceFound` minted its own single-thread executor and
 * nothing ever shut one down. Opening the server-discovery screen repeatedly on a network with
 * several ListenUp servers therefore accumulated live non-daemon threads for the life of the
 * process.
 */
class ResolveExecutorOwnerTest :
    FunSpec({

        test("repeat acquisition shares one executor") {
            val owner = ResolveExecutorOwner()
            try {
                owner.acquire() shouldBe owner.acquire()
            } finally {
                owner.shutdown()
            }
        }

        test("shutdown stops the executor it handed out") {
            val owner = ResolveExecutorOwner()
            val executor = owner.acquire()

            owner.shutdown()

            executor.isShutdown shouldBe true
        }

        test("a stopped-then-restarted discovery gets a live executor") {
            val owner = ResolveExecutorOwner()
            val first = owner.acquire()
            owner.shutdown()

            val second = owner.acquire()
            try {
                second shouldNotBeSameInstanceAs first
                second.isShutdown shouldBe false
            } finally {
                owner.shutdown()
            }
        }

        test("shutdown without a prior acquire is a no-op") {
            ResolveExecutorOwner().shutdown()
        }
    })
