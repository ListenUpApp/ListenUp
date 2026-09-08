package com.calypsan.listenup.web.features.admin

import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.client.presentation.admin.AdminSettingsUiState
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.EventInit
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.asList
import org.w3c.dom.events.Event

private val hosts = mutableListOf<HTMLElement>()

internal fun readyServerSettings(
    serverName: String = "Simon's Library",
    remoteUrl: String = "",
    holdNewBooksForReview: Boolean = false,
    pushNotificationsEnabled: Boolean = true,
    isDirty: Boolean = false,
    isSaving: Boolean = false,
    error: com.calypsan.listenup.api.error.AppError? = null,
): AdminSettingsUiState.Ready =
    AdminSettingsUiState.Ready(
        serverName = serverName,
        remoteUrl = remoteUrl,
        holdNewBooksForReview = holdNewBooksForReview,
        pushNotificationsEnabled = pushNotificationsEnabled,
        isDirty = isDirty,
        isSaving = isSaving,
        error = error,
    )

@Suppress("LongParameterList")
private fun page(
    state: AdminSettingsUiState,
    onServerName: (String) -> Unit = {},
    onRemoteUrl: (String) -> Unit = {},
    onHoldNewBooks: (Boolean) -> Unit = {},
    onPushNotifications: (Boolean) -> Unit = {},
    onSave: () -> Unit = {},
    onClearError: () -> Unit = {},
    onRetry: () -> Unit = {},
    onOpenAdmin: () -> Unit = {},
): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    hosts += host
    renderComposable(root = host) {
        ServerSettingsPage(
            state = state,
            onServerName = onServerName,
            onRemoteUrl = onRemoteUrl,
            onHoldNewBooks = onHoldNewBooks,
            onPushNotifications = onPushNotifications,
            onSave = onSave,
            onClearError = onClearError,
            onRetry = onRetry,
            onOpenAdmin = onOpenAdmin,
        )
    }
    return host
}

private fun input(
    host: HTMLElement,
    id: String,
) = host.querySelector("#$id") as HTMLInputElement

private fun switches(host: HTMLElement) = host.querySelectorAll(".srv-toggle .sw-in").asList().filterIsInstance<HTMLInputElement>()

private fun saveButton(host: HTMLElement) = host.querySelector(".edit-actions button[type=submit]") as HTMLElement

/**
 * Server settings.
 *
 * What these pin: the two text fields batch behind Save and the two switches do not, which is the
 * one thing about this screen a reader can get wrong; a switch that fails reverts in the ViewModel,
 * so the banner here is the only thing that says the flick did not take; and the remote URL says
 * what it is for, because an empty one silently sends invites nobody can follow.
 */
class ServerSettingsPageTest :
    FunSpec({

        afterSpec {
            hosts.forEach { it.remove() }
            hosts.clear()
        }

        test("the fields show what the server is called and where it is reached") {
            val host = page(readyServerSettings(serverName = "Kit", remoteUrl = "https://books.example.com"))

            input(host, "srv-name").value shouldBe "Kit"
            input(host, "srv-remote-url").value shouldBe "https://books.example.com"
        }

        test("each field reports its own changes") {
            val seen = mutableMapOf<String, String>()
            val host =
                page(
                    readyServerSettings(),
                    onServerName = { seen["name"] = it },
                    onRemoteUrl = { seen["url"] = it },
                )

            listOf("srv-name" to "one", "srv-remote-url" to "two").forEach { (id, typed) ->
                val field = input(host, id)
                field.value = typed
                field.dispatchEvent(Event("input", EventInit(bubbles = true)))
            }
            awaitFrame()

            seen shouldBe mapOf("name" to "one", "url" to "two")
        }

        test("the remote URL says what an empty one costs") {
            val host = page(readyServerSettings())

            host.textContent.orEmpty() shouldContain "Invites are sent with this address"
        }

        test("Save is disabled until a field changes") {
            val clean = page(readyServerSettings(isDirty = false))
            val dirty = page(readyServerSettings(isDirty = true))

            saveButton(clean).hasAttribute("disabled") shouldBe true
            saveButton(dirty).hasAttribute("disabled") shouldBe false
        }

        test("Save says so while it is in flight, and cannot be pressed twice") {
            val host = page(readyServerSettings(isDirty = true, isSaving = true))

            saveButton(host).textContent shouldBe "Saving…"
            saveButton(host).hasAttribute("disabled") shouldBe true
        }

        test("submitting the form saves") {
            var saved = 0
            val host = page(readyServerSettings(isDirty = true), onSave = { saved++ })

            saveButton(host).click()
            awaitFrame()

            saved shouldBe 1
        }

        test("the switches show the state they were given") {
            val host = page(readyServerSettings(holdNewBooksForReview = true, pushNotificationsEnabled = false))

            switches(host).map { it.hasAttribute("checked") } shouldContainExactly listOf(true, false)
        }

        test("each switch writes its own setting") {
            val flicked = mutableListOf<String>()
            val host =
                page(
                    readyServerSettings(),
                    onHoldNewBooks = { flicked += "hold=$it" },
                    onPushNotifications = { flicked += "push=$it" },
                )

            // Distinct starting values, so a callback wired to the wrong switch cannot read as
            // right: hold starts off and is turned on, push starts on and is turned off.
            switches(host)[0].click()
            switches(host)[1].click()
            awaitFrame()

            flicked shouldContainExactly listOf("hold=true", "push=false")
        }

        // ⛔ The switches are NOT inside the form and NOT under Save — each writes on the flick.
        // Putting them in would make Save appear to govern them, and Enter in a text field would
        // then submit a form containing controls that had already written themselves.
        test("the switches sit outside the form the Save button belongs to") {
            val host = page(readyServerSettings())

            val form = host.querySelector("form").shouldNotBeNull()
            form.querySelectorAll(".srv-toggle").length shouldBe 0
            host.querySelectorAll(".srv-toggle").length shouldBe 2
        }

        test("the page says the switches need no saving") {
            val host = page(readyServerSettings())

            host.textContent.orEmpty() shouldContain "take effect the moment you flick them"
        }

        // A failed flick has already reverted itself in the ViewModel by the time this renders, so
        // without the banner the switch just springs back and nothing says why.
        test("a failed write is reported, announced, and can be dismissed") {
            var cleared = 0
            val host =
                page(
                    readyServerSettings(error = InternalError(debugInfo = "boom")),
                    onClearError = { cleared++ },
                )

            val alert = host.querySelector(".srv-err-t").shouldNotBeNull()
            alert.getAttribute("role") shouldBe "alert"
            (host.querySelector(".srv-err-x") as HTMLElement).click()
            awaitFrame()

            cleared shouldBe 1
        }

        test("nothing has failed until something does") {
            val host = page(readyServerSettings())

            host.querySelector(".srv-err").shouldBeNull()
        }

        test("a loading page draws a skeleton rather than empty fields") {
            val host = page(AdminSettingsUiState.Loading)

            host.querySelector(".srv-skel").shouldNotBeNull()
            host.querySelector("form").shouldBeNull()
        }

        test("settings that cannot be loaded explain themselves and offer a retry that fires") {
            var retries = 0
            val host = page(AdminSettingsUiState.Error(InternalError(debugInfo = "boom")), onRetry = { retries++ })

            host.querySelector("form").shouldBeNull()
            (host.querySelector(".empty button") as HTMLElement).click()
            awaitFrame()

            retries shouldBe 1
        }

        test("the way back to Admin is offered, and fires") {
            var back = 0
            val host = page(readyServerSettings(), onOpenAdmin = { back++ })

            (host.querySelector(".srv-back") as HTMLElement).click()
            awaitFrame()

            back shouldBe 1
        }
    })
