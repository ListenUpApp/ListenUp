package com.calypsan.listenup.client.data.remote

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.url

/**
 * Pins [isAuthEndpoint] semantics: `encodedPath.startsWith(AUTH_PATH_PREFIX)` rather than
 * the substring match the earlier code used. The substring form would false-positive on
 * any URL containing `/auth/` anywhere, including legitimate API paths like
 * `/api/v1/books/by-author/123`.
 */
class AuthEndpointPredicateTest :
    FunSpec({
        fun request(url: String) = HttpRequestBuilder().apply { url(url) }

        test("returns true for login endpoint") {
            isAuthEndpoint(request("https://server.example.com/api/v1/auth/login")) shouldBe true
        }

        test("returns true for refresh endpoint") {
            isAuthEndpoint(request("https://server.example.com/api/v1/auth/refresh")) shouldBe true
        }

        test("returns true for logout endpoint") {
            isAuthEndpoint(request("https://server.example.com/api/v1/auth/logout")) shouldBe true
        }

        test("returns false for books endpoint") {
            isAuthEndpoint(request("https://server.example.com/api/v1/books")) shouldBe false
        }

        test("returns false for unrelated path containing auth substring") {
            // Pre-W2b.4 `urlString.contains("/auth/")` would match this path (`.../by-author/...`
            // contains `author`) — `encodedPath.startsWith` cannot false-positive.
            isAuthEndpoint(request("https://server.example.com/api/v1/books/by-author/tolkien")) shouldBe false
        }

        test("returns false for auth substring outside prefix") {
            isAuthEndpoint(request("https://server.example.com/something/api/v1/auth/login")) shouldBe false
        }
    })
