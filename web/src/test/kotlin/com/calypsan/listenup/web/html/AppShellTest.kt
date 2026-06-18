package com.calypsan.listenup.web.html

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.html.html
import kotlinx.html.stream.createHTML

class AppShellTest :
    FunSpec({
        test("appShell renders the CSRF meta + htmx header hook when a token is supplied") {
            val html = createHTML().html { appShell(pageTitle = "X", csrfToken = "tok-123") { } }
            html shouldContain """name="csrf-token""""
            html shouldContain "tok-123"
            html shouldContain "htmx:configRequest"
            html shouldContain "X-CSRF-Token"
        }

        test("appShell omits the CSRF meta when no token is supplied") {
            val html = createHTML().html { appShell(pageTitle = "X", csrfToken = null) { } }
            html shouldNotContain "csrf-token"
        }
    })
