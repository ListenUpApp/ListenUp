package com.calypsan.listenup.web.features.notifications

import com.calypsan.listenup.api.dto.NotificationPreferenceDto
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.notifications.NotificationPreference
import com.calypsan.listenup.client.presentation.notifications.NotificationPrefsUiState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

private val hosts = mutableListOf<HTMLElement>()

internal fun pref(
    type: String = "registration_approval",
    inApp: Boolean = true,
    push: Boolean = true,
    pushEligible: Boolean = true,
): NotificationPreferenceDto =
    NotificationPreferenceDto(
        type = type,
        preference = NotificationPreference(inApp = inApp, push = push),
        pushEligible = pushEligible,
    )

private fun page(
    state: NotificationPrefsUiState,
    onSetPreference: (String, NotificationPreference) -> Unit = { _, _ -> },
    onRetry: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    hosts += host
    renderComposable(root = host) {
        NotificationPrefsPage(
            state = state,
            onSetPreference = onSetPreference,
            onRetry = onRetry,
            onOpenSettings = onOpenSettings,
        )
    }
    return host
}

private fun switches(host: HTMLElement): List<HTMLInputElement> {
    val found = host.querySelectorAll(".sw-in")
    return (0 until found.length).map { found.item(it) as HTMLInputElement }
}

/**
 * Which notifications reach you, and how.
 *
 * What these pin: a row is named in words rather than by its wire key, a type this build cannot
 * name gets no row at all (there is nothing to toggle blind), a push-ineligible channel is
 * genuinely `disabled` rather than merely dimmed, each flick reports the WHOLE preference so the
 * untouched channel is not silently cleared, and the page says out loud that push does not arrive
 * in this browser.
 */
class NotificationPrefsPageTest :
    FunSpec({

        afterSpec {
            hosts.forEach { it.remove() }
            hosts.clear()
        }

        test("a row is named in words, not by its wire key") {
            val host = page(NotificationPrefsUiState.Data(listOf(pref(type = "registration_approval"))))

            (host.querySelector(".nprefs-name") as HTMLElement).textContent shouldBe "Pending registrations"
        }

        test("every known type gets a row") {
            val host =
                page(
                    NotificationPrefsUiState.Data(
                        listOf(pref(type = "campfire_invite"), pref(type = "registration_decision")),
                    ),
                )

            val names = host.querySelectorAll(".nprefs-name")
            (names.item(0) as HTMLElement).textContent shouldBe "Campfire invites"
            (names.item(1) as HTMLElement).textContent shouldBe "Registration decisions"
        }

        // A switch labelled `some_future_type` asks someone to decide about something unreadable.
        test("a type this build cannot name gets no row") {
            val host =
                page(
                    NotificationPrefsUiState.Data(
                        listOf(pref(type = "registration_approval"), pref(type = "some_future_type")),
                    ),
                )

            host.querySelectorAll(".nprefs-row").length shouldBe 1
        }

        test("a server whose every type is unknown says so rather than showing a bare heading") {
            val host = page(NotificationPrefsUiState.Data(listOf(pref(type = "some_future_type"))))

            host.querySelector(".nprefs-row") shouldBe null
            host.textContent.orEmpty() shouldContain "Nothing to set yet"
        }

        test("each switch reflects the preference it was given") {
            val host = page(NotificationPrefsUiState.Data(listOf(pref(inApp = true, push = false))))

            val boxes = switches(host)
            boxes[0].checked shouldBe true
            boxes[1].checked shouldBe false
        }

        // Dimmed alone would leave it reachable by keyboard and silent to a screen reader.
        test("a push-ineligible type has a genuinely disabled push switch") {
            val host = page(NotificationPrefsUiState.Data(listOf(pref(pushEligible = false))))

            val boxes = switches(host)
            boxes[0].disabled shouldBe false
            boxes[1].disabled shouldBe true
        }

        // The row stays in the same column down the list; a missing control reads as a fault.
        test("an ineligible push channel is still drawn") {
            val host = page(NotificationPrefsUiState.Data(listOf(pref(pushEligible = false))))

            switches(host).size shouldBe 2
        }

        // `copy(inApp = …)` — sending a bare `NotificationPreference(inApp, false)` would clear push.
        test("flicking in-app carries the untouched push value with it") {
            val changes = mutableListOf<Pair<String, NotificationPreference>>()
            val host =
                page(
                    NotificationPrefsUiState.Data(listOf(pref(type = "campfire_invite", inApp = false, push = true))),
                    onSetPreference = { type, preference -> changes += type to preference },
                )

            switches(host)[0].click()

            changes.size shouldBe 1
            changes[0].first shouldBe "campfire_invite"
            changes[0].second shouldBe NotificationPreference(inApp = true, push = true)
        }

        test("flicking push carries the untouched in-app value with it") {
            val changes = mutableListOf<NotificationPreference>()
            val host =
                page(
                    NotificationPrefsUiState.Data(listOf(pref(inApp = true, push = true))),
                    onSetPreference = { _, preference -> changes += preference },
                )

            switches(host)[1].click()

            changes shouldBe listOf(NotificationPreference(inApp = true, push = false))
        }

        test("a disabled push switch reports nothing when pressed") {
            val changes = mutableListOf<NotificationPreference>()
            val host =
                page(
                    NotificationPrefsUiState.Data(listOf(pref(pushEligible = false))),
                    onSetPreference = { _, preference -> changes += preference },
                )

            switches(host)[1].click()

            changes shouldBe emptyList()
        }

        // Push is an account setting this tab cannot demonstrate. Saying so beats being discovered.
        test("the page says push does not arrive in this browser") {
            val host = page(NotificationPrefsUiState.Data(listOf(pref())))

            (host.querySelector(".nprefs-note") as HTMLElement).textContent.orEmpty() shouldContain "not here"
        }

        test("a failed load explains itself and offers a retry that fires") {
            var retries = 0
            val host =
                page(
                    NotificationPrefsUiState.Error(InternalError()),
                    onRetry = { retries++ },
                )

            host.textContent.orEmpty() shouldContain "can't be loaded"
            (host.querySelector(".empty button") as HTMLElement).click()

            retries shouldBe 1
        }

        test("loading draws a skeleton rather than an empty list that would read as 'none'") {
            val host = page(NotificationPrefsUiState.Loading)

            host.querySelector(".nprefs-skel").shouldNotBeNull()
            host.querySelector(".nprefs-row") shouldBe null
        }

        // A page that cannot show what you asked for must still show the way out of it.
        test("the breadcrumb renders in every state and leads back to Settings") {
            var opened = 0
            val host = page(NotificationPrefsUiState.Loading, onOpenSettings = { opened++ })

            host.querySelector(".crumb").shouldNotBeNull()
            (host.querySelector(".crumb a") as HTMLElement).click()

            opened shouldBe 1
        }
    })
