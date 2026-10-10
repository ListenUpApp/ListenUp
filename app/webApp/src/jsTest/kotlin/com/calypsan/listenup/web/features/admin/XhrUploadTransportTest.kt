package com.calypsan.listenup.web.features.admin

import com.calypsan.listenup.api.dto.uploads.UploadSessionSummary
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.XhrUploadTransport
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.yield
import org.w3c.files.File
import org.w3c.xhr.FormData
import org.w3c.xhr.XMLHttpRequest

/**
 * The browser's file send: a picked file goes to the server through XMLHttpRequest, from disk.
 *
 * ⛔ The thing these hold is that **the body is the picked file, never a copy of its bytes.** Ktor's
 * JS engine copied a whole request body into memory and died on a real audiobook with
 * `RangeError: Invalid array length` — so the request body asserted here is a `FormData` whose part
 * IS a File. The rest is the request the server already accepts from every other client (endpoint,
 * `relPath`, credential), progress that counts bytes the browser reports sending, failures typed
 * exactly as the Ktor path types them, and a Cancel that actually stops the bytes.
 */
class XhrUploadTransportTest :
    FunSpec({
        fun transport(
            requests: MutableList<FakeRequest>,
            onSend: (FakeRequest) -> Unit,
            refreshed: String? = "fresh-token",
        ) = XhrUploadTransport(
            serverUrl = { "http://listenup.local:8080/" },
            accessToken = { "stale-token" },
            refreshAccessToken = { refreshed },
            clientHeaders = mapOf("X-Client-Version" to "9.9.9"),
            newRequest = {
                val request = FakeRequest(onSend)
                requests += request
                request.asXhr()
            },
        )

        val picked = File(arrayOf("twelve bytes").unsafeCast<Array<Any>>(), "01 - Prologue.m4b")

        test("sends the picked file itself as the form's part, to the session with its relPath and credential") {
            val requests = mutableListOf<FakeRequest>()
            val result =
                transport(requests, onSend = { it.answer(200, SUMMARY_JSON) })
                    .upload("s1", "Dune/01 - Prologue.m4b", picked, picked.name) { _, _ -> }

            result shouldBe AppResult.Success(UploadSessionSummary("s1", 1, 12))
            val request = requests.single()
            request.method shouldBe "POST"
            request.url shouldBe
                "http://listenup.local:8080/api/v1/admin/uploads/s1/file?relPath=Dune%2F01+-+Prologue.m4b"
            request.headers["Authorization"] shouldBe "Bearer stale-token"
            request.headers["X-Client-Version"] shouldBe "9.9.9"
            // The browser writes the multipart Content-Type, boundary included; setting one breaks it.
            request.headers.containsKey("Content-Type") shouldBe false

            val body = request.body.shouldBeInstanceOf<FormData>()
            val part =
                body
                    .asDynamic()
                    .get("file")
                    .unsafeCast<Any>()
                    .shouldBeInstanceOf<File>()
            part.name shouldBe "01 - Prologue.m4b"
            part.size shouldBe picked.size
        }

        test("reports the bytes the browser says it sent, and an unknown total as unknown") {
            val requests = mutableListOf<FakeRequest>()
            val reported = mutableListOf<Pair<Long, Long?>>()
            transport(
                requests,
                onSend = { request ->
                    request.progress(loaded = 7.0, total = 12.0, lengthComputable = true)
                    request.answer(200, SUMMARY_JSON)
                },
            ).upload("s1", "01.m4b", picked, picked.name) { sent, total -> reported += sent to total }

            reported.last() shouldBe (7L to 12L)

            val unknown = mutableListOf<Pair<Long, Long?>>()
            transport(
                requests,
                onSend = { request ->
                    request.progress(loaded = 3.0, total = 0.0, lengthComputable = false)
                    request.answer(200, SUMMARY_JSON)
                },
            ).upload("s1", "01.m4b", picked, picked.name) { sent, total -> unknown += sent to total }

            unknown shouldContainExactly listOf(3L to null)
        }

        test("a refused file is a typed failure, mapped the way the Ktor path maps a status") {
            val requests = mutableListOf<FakeRequest>()

            val tooLarge =
                transport(requests, onSend = { it.answer(413, "") })
                    .upload("s1", "01.m4b", picked, picked.name) { _, _ -> }
            val broken =
                transport(requests, onSend = { it.answer(500, "") })
                    .upload("s1", "01.m4b", picked, picked.name) { _, _ -> }

            tooLarge
                .shouldBeInstanceOf<AppResult.Failure>()
                .error
                .shouldBeInstanceOf<TransportError.Server4xx>()
                .statusCode shouldBe 413
            broken.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<TransportError.Server5xx>()
        }

        test("a dropped connection is a retryable network failure, not a throw") {
            val requests = mutableListOf<FakeRequest>()

            val dropped =
                transport(requests, onSend = { it.fail() })
                    .upload("s1", "01.m4b", picked, picked.name) { _, _ -> }

            val error = dropped.shouldBeInstanceOf<AppResult.Failure>().error
            error.shouldBeInstanceOf<TransportError.NetworkUnavailable>()
            error.isRetryable shouldBe true
        }

        test("a 401 rotates the credential once and sends again, as the Ktor bearer plugin does") {
            val requests = mutableListOf<FakeRequest>()
            val result =
                transport(
                    requests,
                    onSend = { request ->
                        if (request.headers["Authorization"] == "Bearer stale-token") {
                            request.answer(401, "")
                        } else {
                            request.answer(200, SUMMARY_JSON)
                        }
                    },
                ).upload("s1", "01.m4b", picked, picked.name) { _, _ -> }

            result.shouldBeInstanceOf<AppResult.Success<UploadSessionSummary>>()
            requests.map { it.headers["Authorization"] } shouldContainExactly
                listOf("Bearer stale-token", "Bearer fresh-token")
        }

        test("a 401 the refresh cannot heal is the session ending, sent once") {
            val requests = mutableListOf<FakeRequest>()
            val result =
                transport(requests, onSend = { it.answer(401, "") }, refreshed = null)
                    .upload("s1", "01.m4b", picked, picked.name) { _, _ -> }

            result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<AuthError.SessionExpired>()
            requests.size shouldBe 1
        }

        test("postFile sends the picked file as the named part to the path, and answers the body") {
            val requests = mutableListOf<FakeRequest>()
            val result =
                transport(requests, onSend = { it.answer(200, ARCHIVE_JSON) })
                    .postFile(
                        path = "/api/v1/admin/import/abs/upload",
                        partName = "file",
                        file = picked,
                        filename = "library.audiobookshelf",
                        timeoutMs = 600_000,
                    )

            result shouldBe AppResult.Success(ARCHIVE_JSON)
            val request = requests.single()
            request.method shouldBe "POST"
            request.url shouldBe "http://listenup.local:8080/api/v1/admin/import/abs/upload"
            request.timeout shouldBe 600_000
            request.headers["Authorization"] shouldBe "Bearer stale-token"
            request.headers["X-Client-Version"] shouldBe "9.9.9"
            request.headers.containsKey("Content-Type") shouldBe false
            val part =
                request.body
                    .shouldBeInstanceOf<FormData>()
                    .asDynamic()
                    .get("file")
                    .unsafeCast<Any>()
                    .shouldBeInstanceOf<File>()
            part.name shouldBe "library.audiobookshelf"
            part.size shouldBe picked.size
        }

        test("postFile heals a 401 once, and maps a refusal by its status") {
            val requests = mutableListOf<FakeRequest>()
            val healed =
                transport(
                    requests,
                    onSend = { request ->
                        if (request.headers["Authorization"] == "Bearer stale-token") {
                            request.answer(401, "")
                        } else {
                            request.answer(200, ARCHIVE_JSON)
                        }
                    },
                ).postFile(path = "/backup", partName = "backup", file = picked, filename = picked.name)
            val refused =
                transport(requests, onSend = { it.answer(422, "") })
                    .postFile(path = "/backup", partName = "backup", file = picked, filename = picked.name)

            healed shouldBe AppResult.Success(ARCHIVE_JSON)
            requests.take(2).map { it.headers["Authorization"] } shouldContainExactly
                listOf("Bearer stale-token", "Bearer fresh-token")
            refused
                .shouldBeInstanceOf<AppResult.Failure>()
                .error
                .shouldBeInstanceOf<TransportError.Server4xx>()
                .statusCode shouldBe 422
        }

        test("cancelling the upload aborts the request, so Cancel stops the bytes") {
            val requests = mutableListOf<FakeRequest>()
            coroutineScope {
                // Never answers: the request is in flight until something stops it.
                val upload =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        transport(requests, onSend = {}).upload("s1", "01.m4b", picked, picked.name) { _, _ -> }
                    }
                while (requests.singleOrNull()?.body == null) yield()

                upload.cancel()
                yield()

                requests.single().aborted shouldBe true
            }
        }
    })

private const val SUMMARY_JSON = """{"sessionId":"s1","fileCount":1,"totalBytes":12}"""

private const val ARCHIVE_JSON = """{"id":"abs-1"}"""

/**
 * A stand-in for the browser's XMLHttpRequest, built as a plain JS object so the transport's
 * external-property reads and writes land on it exactly as they would on the real one.
 *
 * [onSend] scripts the server: it runs when the transport sends, and answers, fails, or leaves the
 * request hanging.
 */
private class FakeRequest(
    private val onSend: (FakeRequest) -> Unit,
) {
    var method: String? = null
    var url: String? = null
    val headers = mutableMapOf<String, String>()
    var body: Any? = null
    var aborted = false

    /** The timeout the transport set on the request, in milliseconds. */
    val timeout: Int get() = request.timeout.unsafeCast<Int>()

    private val request: dynamic = js("({ upload: {}, status: 0, responseText: '' })")

    init {
        request.open = { method: String, url: String ->
            this.method = method
            this.url = url
        }
        request.setRequestHeader = { name: String, value: String -> headers[name] = value }
        request.send = { body: Any? ->
            this.body = body
            onSend(this)
        }
        request.abort = { aborted = true }
    }

    fun asXhr(): XMLHttpRequest = request.unsafeCast<XMLHttpRequest>()

    fun answer(
        status: Int,
        body: String,
    ) {
        request.status = status
        request.responseText = body
        request.onload(js("({})"))
    }

    fun fail() {
        request.onerror(js("({})"))
    }

    fun progress(
        loaded: Double,
        total: Double,
        lengthComputable: Boolean,
    ) {
        val event: dynamic = js("({})")
        event.loaded = loaded
        event.total = total
        event.lengthComputable = lengthComputable
        request.upload.onprogress(event)
    }
}
