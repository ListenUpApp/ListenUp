package com.calypsan.listenup.web.features.notifications

import com.calypsan.listenup.api.notifications.NotificationEvent
import com.calypsan.listenup.client.domain.model.AppNotification
import com.calypsan.listenup.client.presentation.notifications.NotificationsUiState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement

private const val NOW_MS = 1_800_000_000_000L

private const val ONE_HOUR_MS = 3_600_000L

private val hosts = mutableListOf<HTMLElement>()

internal fun notification(
    id: String = "n1",
    event: NotificationEvent? = NotificationEvent.RegistrationApproval(userId = "u1"),
    createdAt: Long = NOW_MS - ONE_HOUR_MS,
    readAt: Long? = null,
): AppNotification =
    AppNotification(
        id = id,
        type = event?.wireType ?: "unknown_future_type",
        event = event,
        createdAt = createdAt,
        readAt = readAt,
    )

private fun page(
    state: NotificationsUiState,
    onOpen: (AppNotification) -> Unit = {},
): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    hosts += host
    renderComposable(root = host) {
        NotificationsPage(state = state, nowMs = NOW_MS, onOpen = onOpen)
    }
    return host
}

/**
 * The notification inbox.
 *
 * What these pin: a row says what its event actually is rather than its wire type, a type this
 * build does not know still renders instead of vanishing (which would make the badge count things
 * nobody can see), unread is marked by weight and a dot rather than colour alone, and the whole
 * row is one control — because marking read is a real thing to do to a notification even when it
 * has nowhere to send you.
 */
class NotificationsPageTest :
    FunSpec({

        afterSpec {
            hosts.forEach { it.remove() }
            hosts.clear()
        }

        test("a row says what happened, in words") {
            val host = page(NotificationsUiState.Data(listOf(notification())))

            (host.querySelector(".ntf-t") as HTMLElement).textContent shouldBe "Registration waiting"
            (host.querySelector(".ntf-b") as HTMLElement).textContent.orEmpty() shouldContain "waiting for approval"
        }

        // An older web client against a newer server. Dropping the row would leave the badge
        // counting something the page refuses to show.
        test("a type this build does not know still renders, generically") {
            val host = page(NotificationsUiState.Data(listOf(notification(event = null))))

            (host.querySelector(".ntf-t") as HTMLElement).textContent shouldBe "Notification"
            (host.querySelector(".ntf-b") as HTMLElement).textContent.orEmpty() shouldContain "Update ListenUp"
        }

        test("the row carries when it arrived, relative to now") {
            val host = page(NotificationsUiState.Data(listOf(notification(createdAt = NOW_MS - ONE_HOUR_MS))))

            (host.querySelector(".ntf-when") as HTMLElement).textContent shouldBe "1 hour ago"
        }

        // Weight and a dot, never colour alone.
        test("unread is marked in more than one way, and read is not") {
            val unread = page(NotificationsUiState.Data(listOf(notification(readAt = null))))
            val read = page(NotificationsUiState.Data(listOf(notification(readAt = NOW_MS))))

            (unread.querySelector(".ntf-row") as HTMLElement).classList.contains("unread") shouldBe true
            (read.querySelector(".ntf-row") as HTMLElement).classList.contains("unread") shouldBe false
        }

        test("an unread row says so to a screen reader, not only to an eye") {
            val host = page(NotificationsUiState.Data(listOf(notification(readAt = null))))

            (host.querySelector(".ntf-row") as HTMLElement).getAttribute("aria-label").orEmpty() shouldContain "unread"
        }

        // The dot's space is kept when read so the column does not reflow as rows are opened.
        test("a read row keeps the dot's space") {
            val host = page(NotificationsUiState.Data(listOf(notification(readAt = NOW_MS))))

            host.querySelector(".ntf-dot").shouldNotBeNull()
        }

        test("the whole row is one control, and reports the notification it is") {
            val opened = mutableListOf<String>()
            val host =
                page(
                    NotificationsUiState.Data(listOf(notification(id = "n1"), notification(id = "n2"))),
                    onOpen = { opened += it.id },
                )

            val rows = host.querySelectorAll(".ntf-row")
            (rows.item(1) as HTMLElement).tagName shouldBe "BUTTON"
            (rows.item(1) as HTMLElement).click()

            opened shouldBe listOf("n2")
        }

        test("rows render in the order the state gave them") {
            val host =
                page(
                    NotificationsUiState.Data(
                        listOf(
                            notification(id = "n1", event = NotificationEvent.RegistrationApproval(userId = "u1")),
                            notification(id = "n2", event = NotificationEvent.RegistrationDecision(userId = "u2", approved = true)),
                        ),
                    ),
                )

            val titles = host.querySelectorAll(".ntf-t")
            (titles.item(0) as HTMLElement).textContent shouldBe "Registration waiting"
            (titles.item(1) as HTMLElement).textContent shouldBe "You're in"
        }

        test("an empty inbox says so rather than showing an empty list") {
            val host = page(NotificationsUiState.Empty)

            host.querySelector(".ntf-row") shouldBe null
            host.textContent.orEmpty() shouldContain "Nothing waiting"
        }

        test("loading draws a skeleton, not an empty state that would be a lie") {
            val host = page(NotificationsUiState.Loading)

            host.querySelector(".ntf-skel").shouldNotBeNull()
            host.textContent.orEmpty().contains("Nothing waiting") shouldBe false
        }
    })

/**
 * The web copy, against the `:app:sharedUI` strings it deliberately duplicates.
 *
 * ⛔ These literals are the sharedUI `strings.xml` values verbatim. They are asserted here rather
 * than imported because Compose Multiplatform resources do not reach Compose HTML — so this spec
 * IS the link between the two, and a wording change on either side should land here as a failure
 * naming both.
 */
class NotificationCopyTest :
    FunSpec({

        test("a registration awaiting approval reads as sharedUI writes it") {
            val copy = notificationCopy(NotificationEvent.RegistrationApproval(userId = "u1"))

            copy.title shouldBe "Registration waiting"
            copy.body shouldBe "Someone is waiting for approval to join your server."
        }

        // The one case whose copy turns on a field rather than on the type alone.
        test("a registration decision reads differently approved and denied") {
            val approved = notificationCopy(NotificationEvent.RegistrationDecision(userId = "u1", approved = true))
            val denied = notificationCopy(NotificationEvent.RegistrationDecision(userId = "u1", approved = false))

            approved.title shouldBe "You're in"
            approved.body shouldBe "Your registration was approved. Welcome to ListenUp."
            denied.title shouldBe "Registration declined"
            denied.body shouldBe "An admin declined your registration."
        }

        test("a campfire invite reads as sharedUI writes it") {
            val copy =
                notificationCopy(
                    NotificationEvent.CampfireInvite(campfireId = "c1", bookId = "b1", inviterUserId = "u1"),
                )

            copy.title shouldBe "Campfire invite"
            copy.body shouldBe "You've been invited to listen together."
        }

        test("an unknown type gets copy that tells the reader what to do about it") {
            val copy = notificationCopy(null)

            copy.title shouldBe "Notification"
            copy.body shouldBe "Update ListenUp to see this notification."
        }
    })
