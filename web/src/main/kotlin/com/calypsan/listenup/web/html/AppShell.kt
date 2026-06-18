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
import kotlinx.html.unsafe

/**
 * The base HTML document every web page renders into. Pages supply body content via
 * [content]; the shell provides the head (Tailwind stylesheet, htmx runtime) and, when a
 * [csrfToken] is supplied, the CSRF `<meta>` plus an htmx `configRequest` hook that echoes
 * the token back as the `X-CSRF-Token` header on every htmx request (the double-submit pair
 * checked by [com.calypsan.listenup.web.security.webCsrfConfig]).
 */
fun HTML.appShell(
    pageTitle: String = "ListenUp",
    csrfToken: String? = null,
    content: MAIN.() -> Unit,
) {
    lang = "en"
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        title { +pageTitle }
        link(rel = "stylesheet", href = "/assets/app.css")
        script(src = "/assets/htmx.min.js") {}
        script(src = "/assets/htmx-ext-sse.js") {}
        if (csrfToken != null) {
            meta(name = "csrf-token", content = csrfToken)
            script { unsafe { +CSRF_HTMX_HOOK } }
        }
    }
    body {
        main(classes = "mx-auto") {
            content()
        }
    }
}

private const val CSRF_HTMX_HOOK =
    """
    document.body.addEventListener('htmx:configRequest', function (e) {
      var meta = document.querySelector('meta[name=csrf-token]');
      if (meta) { e.detail.headers['X-CSRF-Token'] = meta.content; }
    });
    """
