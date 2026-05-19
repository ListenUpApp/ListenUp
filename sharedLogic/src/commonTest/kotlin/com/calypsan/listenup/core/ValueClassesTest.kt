package com.calypsan.listenup.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Tests for the client-only value classes — `ServerUrl` validation +
 * normalization. Token validation lives on the contract types in
 * `:shared/commonMain/api/dto/auth/Identifiers.kt`; the client no longer
 * defines its own `AccessToken`/`RefreshToken`.
 */
class ValueClassesTest :
    FunSpec({

        test("ServerUrl accepts https URL") {
            ServerUrl("https://api.example.com").value shouldBe "https://api.example.com"
        }

        test("ServerUrl accepts http URL") {
            ServerUrl("http://localhost:8080").value shouldBe "http://localhost:8080"
        }

        test("ServerUrl removes trailing slash") {
            ServerUrl("https://api.example.com/").value shouldBe "https://api.example.com"
        }

        test("ServerUrl removes multiple trailing slashes") {
            ServerUrl("https://api.example.com///").value shouldBe "https://api.example.com"
        }

        test("ServerUrl preserves path without trailing slash") {
            ServerUrl("https://api.example.com/v1/api").value shouldBe "https://api.example.com/v1/api"
        }

        test("ServerUrl removes trailing slash from path") {
            ServerUrl("https://api.example.com/v1/api/").value shouldBe "https://api.example.com/v1/api"
        }

        test("ServerUrl rejects blank URL") {
            shouldThrow<IllegalArgumentException> { ServerUrl("") }
        }

        test("ServerUrl rejects URL without protocol") {
            shouldThrow<IllegalArgumentException> { ServerUrl("api.example.com") }
        }

        test("ServerUrl rejects URL with ftp protocol") {
            shouldThrow<IllegalArgumentException> { ServerUrl("ftp://files.example.com") }
        }

        test("ServerUrl toString returns normalized value") {
            ServerUrl("https://api.example.com/").toString() shouldBe "https://api.example.com"
        }

        test("ServerUrl with port number is preserved") {
            ServerUrl("http://localhost:3000").value shouldBe "http://localhost:3000"
        }

        test("ServerUrl with query string is preserved") {
            ServerUrl("https://api.example.com?key=value").value shouldBe "https://api.example.com?key=value"
        }
    })
