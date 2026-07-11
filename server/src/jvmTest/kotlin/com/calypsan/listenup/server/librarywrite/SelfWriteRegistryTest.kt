package com.calypsan.listenup.server.librarywrite

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.io.files.Path

class SelfWriteRegistryTest :
    FunSpec({
        fun registry(nowMs: () -> Long) = SelfWriteRegistry(clock = nowMs)

        test("registered path is claimed exactly while unexpired") {
            var now = 1_000L
            val reg = registry { now }
            val p = Path("/lib/Author/Book/listenup.json")

            reg.register(p, ttlMs = 30_000)
            reg.isSelfWrite(p) shouldBe true

            now += 30_001
            reg.isSelfWrite(p) shouldBe false // expired
        }

        test("unregistered sibling path is never claimed") {
            val reg = registry { 0L }
            reg.register(Path("/lib/A/x.json"), ttlMs = 30_000)
            reg.isSelfWrite(Path("/lib/A/y.json")) shouldBe false
        }

        test("consume-on-match removes the claim so a SECOND event is external") {
            val reg = registry { 0L }
            val p = Path("/lib/A/x.json")
            reg.register(p, ttlMs = 30_000)
            reg.consumeIfSelfWrite(p) shouldBe true // our write event
            reg.consumeIfSelfWrite(p) shouldBe false // a later edit = human
        }

        test("release clears a claim early (write failed, nothing will arrive)") {
            val reg = registry { 0L }
            val p = Path("/lib/A/x.json")
            reg.register(p, ttlMs = 30_000)
            reg.release(p)
            reg.isSelfWrite(p) shouldBe false
        }
    })
