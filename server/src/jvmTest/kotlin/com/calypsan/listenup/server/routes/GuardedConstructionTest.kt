package com.calypsan.listenup.server.routes

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.core.spec.style.FunSpec

/**
 * Pins `guardedConstruction` — the registration-factory guard (finding #6b). A service-construction
 * failure (a mis-wired `as` cast, a failing `copyWith`) runs OUTSIDE the generated `guard`, so a raw
 * throw would ship the exception class + message + stacktrace to the client via kotlinx.rpc's default.
 * The wrapper must convert it into a sanitized throw that leaks nothing but a correlation id.
 */
class GuardedConstructionTest :
    FunSpec({

        test("passes a successful construction through untouched") {
            val out = guardedConstruction { "service-instance" }
            out shouldBe "service-instance"
        }

        test("sanitizes a construction failure — no class/message/path leaks, only a cid") {
            val thrown =
                shouldThrow<IllegalStateException> {
                    guardedConstruction<String> {
                        throw ClassCastException(
                            "class BookServiceImpl cannot be cast to class ScannerServiceImpl at /var/lib/listenup/db",
                        )
                    }
                }

            val message = thrown.message.orEmpty()
            message shouldContain "cid="
            // The original throwable's class name, message, SQL, paths, and hostnames must NOT survive.
            message shouldNotContain "ClassCastException"
            message shouldNotContain "ScannerServiceImpl"
            message shouldNotContain "/var/lib/listenup"
        }

        test("re-raises CancellationException rather than sanitizing it") {
            shouldThrow<kotlinx.coroutines.CancellationException> {
                guardedConstruction<String> { throw kotlinx.coroutines.CancellationException("cancel") }
            }
        }
    })
