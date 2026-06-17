package com.calypsan.listenup.web.html

import kotlinx.html.HTML
import kotlinx.html.MAIN
import kotlinx.html.body
import kotlinx.html.head
import kotlinx.html.lang
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.script
import kotlinx.html.title

/**
 * The base HTML document every web page is rendered into. Pages supply their body
 * content via [content]; the shell provides the head (Tailwind stylesheet, htmx runtime,
 * CSRF meta — CSRF wired in Phase 1B) and a consistent outer layout.
 */
fun HTML.appShell(
    pageTitle: String = "ListenUp",
    content: MAIN.() -> Unit,
) {
    lang = "en"
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        title { +pageTitle }
        link(rel = "stylesheet", href = "/assets/app.css")
        script(src = "/assets/htmx.min.js") {}
    }
    body {
        main(classes = "mx-auto") {
            content()
        }
    }
}
