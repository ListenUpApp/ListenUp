package com.calypsan.listenup.web.features.licences

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.browser.window
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.js.Promise
import kotlin.time.Duration.Companion.seconds

/**
 * The fetch behind the Licences page.
 *
 * ⛔ This spec exists because a sabotage pass found nothing covered it: swapping the failure branch
 * for `Ready(emptyList())` broke **zero** tests, because every page spec drives `fixedLicences`.
 * That branch is the one thing an attribution page must not get wrong — claiming the client uses no
 * open-source software when the truth is that a file did not load.
 */
class LicencesStoreTest :
    FunSpec({
        val realFetch = window.asDynamic().fetch

        afterTest { window.asDynamic().fetch = realFetch }

        suspend fun settled(session: LicencesSession): LicencesUiState =
            withTimeout(5.seconds) {
                var state = session.state.value
                while (state is LicencesUiState.Loading) {
                    yield()
                    state = session.state.value
                }
                state
            }

        test("a manifest that cannot be fetched reports an error, never an empty library list") {
            window.asDynamic().fetch = { _: Any?, _: Any? -> Promise.reject(RuntimeException("offline")) }

            val session = graphLicences()()
            try {
                settled(session).shouldBeInstanceOf<LicencesUiState.Error>()
            } finally {
                session.close()
            }
        }

        test("a non-2xx response is an error too, not an empty list") {
            window.asDynamic().fetch = { _: Any?, _: Any? ->
                respondWith(ok = false, status = 404)
            }

            val session = graphLicences()()
            try {
                settled(session).shouldBeInstanceOf<LicencesUiState.Error>()
            } finally {
                session.close()
            }
        }

        test("a manifest that loads becomes the libraries it names") {
            val body = """{"libraries":[{"uniqueId":"npm:ws","name":"ws","licenses":["MIT"]}]}"""
            window.asDynamic().fetch = { _: Any?, _: Any? ->
                respondWith(ok = true, status = 200, body = body)
            }

            val session = graphLicences()()
            try {
                val ready = settled(session).shouldBeInstanceOf<LicencesUiState.Ready>()
                ready.libraries.map { it.name } shouldBe listOf("ws")
            } finally {
                session.close()
            }
        }

        // The manifest gains fields as the generator grows; a reader on an older bundle should still
        // get a page rather than a parse failure.
        test("fields this build does not know about do not fail the parse") {
            val body = """{"libraries":[{"uniqueId":"npm:ws","name":"ws","licenses":["MIT"],"fundingLinks":[]}],"meta":1}"""
            window.asDynamic().fetch = { _: Any?, _: Any? ->
                respondWith(ok = true, status = 200, body = body)
            }

            val session = graphLicences()()
            try {
                settled(session).shouldBeInstanceOf<LicencesUiState.Ready>().libraries.size shouldBe 1
            } finally {
                session.close()
            }
        }
    })

/**
 * A stand-in `Response`, already wrapped in the promise `fetch` hands back.
 *
 * Returns the promise rather than the object because `Promise.resolve(someDynamic)` cannot infer its
 * type parameter — spelling it once here beats annotating every call site.
 */
private fun respondWith(
    ok: Boolean,
    status: Int,
    body: String? = null,
): Promise<dynamic> {
    val o = js("({})")
    o.ok = ok
    o.status = status
    if (body != null) o.text = { Promise.resolve<String>(body) }
    return Promise.resolve<dynamic>(o)
}
