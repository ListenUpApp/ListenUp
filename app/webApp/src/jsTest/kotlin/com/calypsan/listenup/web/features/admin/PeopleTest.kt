package com.calypsan.listenup.web.features.admin

import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.InviteInfo
import com.calypsan.listenup.client.domain.model.UserPermissions
import com.calypsan.listenup.client.presentation.admin.CreateInviteErrorType
import com.calypsan.listenup.client.presentation.admin.CreateInviteField
import com.calypsan.listenup.client.presentation.admin.CreateInviteStatus
import com.calypsan.listenup.client.presentation.admin.CreateInviteUiState
import com.calypsan.listenup.client.presentation.admin.UserDetailUiState
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.asList
import org.w3c.dom.events.Event

internal fun adminUser(
    id: String = "u1",
    email: String = "ada@example.com",
    displayName: String? = "Ada Lovelace",
    isRoot: Boolean = false,
    role: String = "member",
    status: String = "active",
    canEdit: Boolean = true,
    canShare: Boolean = true,
) = AdminUserInfo(
    id = id,
    email = email,
    displayName = displayName,
    firstName = null,
    lastName = null,
    isRoot = isRoot,
    role = role,
    status = status,
    permissions = UserPermissions(canEdit = canEdit, canShare = canShare),
    createdAt = "2026-01-01T00:00:00Z",
)

internal fun readyUser(
    user: AdminUserInfo = adminUser(),
    isSaving: Boolean = false,
    error: com.calypsan.listenup.api.error.AppError? = null,
) = UserDetailUiState.Ready(
    user = user,
    canEdit = user.permissions.canEdit,
    canShare = user.permissions.canShare,
    isProtected = user.isProtected,
    isSaving = isSaving,
    error = error,
)

internal fun invite(
    email: String = "ada@example.com",
    url: String = "https://listen.example/join?code=ABC123",
) = InviteInfo(
    id = "i1",
    code = "ABC123",
    name = "Ada",
    email = email,
    role = "member",
    expiresAt = "2026-02-01T00:00:00Z",
    claimedAt = null,
    url = url,
    createdAt = "2026-01-01T00:00:00Z",
)

private fun buttons(host: HTMLElement): List<HTMLButtonElement> =
    host.querySelectorAll("button").asList().filterIsInstance<HTMLButtonElement>()

private fun button(
    host: HTMLElement,
    label: String,
): HTMLButtonElement? = buttons(host).firstOrNull { it.textContent?.trim() == label }

private fun text(
    host: HTMLElement,
    selector: String,
): String? = (host.querySelector(selector) as? HTMLElement)?.textContent?.trim()

private fun type(
    host: HTMLElement,
    value: String,
) {
    val field = host.querySelector(".f-input") as HTMLInputElement
    field.value = value
    field.dispatchEvent(Event("input", org.w3c.dom.EventInit(bubbles = true)))
}

/**
 * Inviting somebody, and looking at one member.
 *
 * ⛔ The invite form is the bigger of the two: before it, an admin on the web could revoke an
 * invite but never create one, so on a server with registration closed the browser could not add a
 * person at all.
 */
class PeopleTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun invitePage(
            state: CreateInviteUiState,
            onCreate: (String, String, Int) -> Unit = { _, _, _ -> },
            onClearError: () -> Unit = {},
            onCreateAnother: () -> Unit = {},
            onCopy: (String) -> Unit = {},
            onOpenAdmin: () -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                CreateInvitePage(
                    state = state,
                    onCreate = onCreate,
                    onClearError = onClearError,
                    onCreateAnother = onCreateAnother,
                    onCopy = onCopy,
                    onOpenAdmin = onOpenAdmin,
                )
            }

        fun userPage(
            state: UserDetailUiState,
            onToggleCanEdit: () -> Unit = {},
            onToggleCanShare: () -> Unit = {},
            onOpenAdmin: () -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                UserDetailPage(
                    state = state,
                    onToggleCanEdit = onToggleCanEdit,
                    onToggleCanShare = onToggleCanShare,
                    onOpenAdmin = onOpenAdmin,
                )
            }

        val idle = CreateInviteUiState.Ready(CreateInviteStatus.Idle)

        test("an invite needs an address before it can be made") {
            // ⛔ A blank email cannot make an invite, and letting it through would answer with a
            // server validation error saying what the form already knew.
            val host = invitePage(idle)

            button(host, "Create invite").shouldNotBeNull().hasAttribute("disabled") shouldBe true

            type(host, "ada@example.com")
            awaitFrame()

            button(host, "Create invite").shouldNotBeNull().hasAttribute("disabled") shouldBe false
        }

        test("creating reports the address, the level and the expiry together") {
            val made = mutableListOf<Triple<String, String, Int>>()
            val host = invitePage(idle, onCreate = { email, role, days -> made += Triple(email, role, days) })

            type(host, "  ada@example.com  ")
            awaitFrame()
            button(host, "Create invite").shouldNotBeNull().click()
            awaitFrame()

            // ⛔ Arrives trimmed, and this spec is what holds the field to `InputType.Email`: the
            // browser sanitises an email input's value itself. A pasted address routinely carries
            // a trailing space, and the server would reject it for a reason the reader cannot see.
            made shouldContainExactly listOf(Triple("ada@example.com", "member", 7))
        }

        test("the access level and expiry are both changeable, and say which is chosen") {
            val made = mutableListOf<Triple<String, String, Int>>()
            val host = invitePage(idle, onCreate = { email, role, days -> made += Triple(email, role, days) })

            type(host, "ada@example.com")
            awaitFrame()
            button(host, "AdminCan manage users and invites").shouldNotBeNull().click()
            button(host, "30 days").shouldNotBeNull().click()
            awaitFrame()

            buttons(host)
                .first { it.classList.contains("inv-opt") && it.classList.contains("on") }
                .textContent shouldContain "Admin"
            button(host, "30 days").shouldNotBeNull().getAttribute("aria-pressed") shouldBe "true"
            button(host, "1 day").shouldNotBeNull().getAttribute("aria-pressed") shouldBe "false"

            button(host, "Create invite").shouldNotBeNull().click()
            awaitFrame()

            made shouldContainExactly listOf(Triple("ada@example.com", "admin", 30))
        }

        test("a day is a day, not 1 days") {
            expiryLabel(1) shouldBe "1 day"
            expiryLabel(30) shouldBe "30 days"
        }

        test("submitting says so, and cannot be submitted twice") {
            val host = invitePage(CreateInviteUiState.Ready(CreateInviteStatus.Submitting))

            type(host, "ada@example.com")
            awaitFrame()
            button(host, "Creating…").shouldNotBeNull().hasAttribute("disabled") shouldBe true
        }

        test("an address already in use is said under the field, not twice") {
            // ⛔ The same sentence under the input and above the button reads as two problems.
            emailError(CreateInviteErrorType.EmailInUse) shouldBe "Somebody with that address is already here."
            otherError(CreateInviteErrorType.EmailInUse).shouldBeNull()

            val host = invitePage(CreateInviteUiState.Ready(CreateInviteStatus.Error(CreateInviteErrorType.EmailInUse)))
            host.querySelectorAll(".inv-err").length shouldBe 1
        }

        test("a malformed address highlights the field it is about") {
            val bad = CreateInviteErrorType.ValidationError(CreateInviteField.EMAIL)
            emailError(bad) shouldBe "That does not look like an email address."
            otherError(bad).shouldBeNull()

            val host = invitePage(CreateInviteUiState.Ready(CreateInviteStatus.Error(bad)))
            (host.querySelector(".f-box") as HTMLElement).classList.contains("err") shouldBe true
        }

        test("a failure that is nobody's field is said above the button") {
            otherError(CreateInviteErrorType.NetworkError(null)) shouldBe
                "Couldn't reach the server. Check your connection and try again."
            otherError(CreateInviteErrorType.NetworkError("Offline.")) shouldBe "Offline."
            otherError(CreateInviteErrorType.ServerError(null)) shouldBe "The server couldn't create that invite."
            emailError(CreateInviteErrorType.ServerError("boom")).shouldBeNull()
        }

        test("typing clears an error that is no longer about what is on screen") {
            var cleared = 0
            val host =
                invitePage(
                    CreateInviteUiState.Ready(CreateInviteStatus.Error(CreateInviteErrorType.EmailInUse)),
                    onClearError = { cleared++ },
                )

            type(host, "grace@example.com")
            awaitFrame()

            cleared shouldBe 1
        }

        test("a made invite shows the whole link, selectable, beside a Copy") {
            // ⛔ Rendered in full rather than hidden behind the button: `navigator.clipboard` needs
            // a secure context, and a LAN server over plain http has none — a page whose only way
            // out was a button that silently does nothing would strand the admin.
            val copied = mutableListOf<String>()
            val host =
                invitePage(
                    CreateInviteUiState.Ready(CreateInviteStatus.Success(invite())),
                    onCopy = { copied += it },
                )

            text(host, ".inv-url") shouldBe "https://listen.example/join?code=ABC123"
            text(host, ".inv-t") shouldBe "ada@example.com"
            button(host, "Copy").shouldNotBeNull().click()
            awaitFrame()

            copied shouldContainExactly listOf("https://listen.example/join?code=ABC123")
        }

        test("a made invite offers both of the things you might do next") {
            var another = 0
            var done = 0
            val host =
                invitePage(
                    CreateInviteUiState.Ready(CreateInviteStatus.Success(invite())),
                    onCreateAnother = { another++ },
                    onOpenAdmin = { done++ },
                )

            // The form is gone — a success that still showed the inputs would invite a second
            // accidental invite to the same person.
            host.querySelector(".f-input").shouldBeNull()

            button(host, "Create another").shouldNotBeNull().click()
            button(host, "Done").shouldNotBeNull().click()
            awaitFrame()

            another shouldBe 1
            done shouldBe 1
        }

        test("a member's page names them and what they are") {
            val host = userPage(readyUser(adminUser(displayName = "Ada Lovelace", email = "ada@example.com")))

            text(host, ".usr-t") shouldBe "Ada Lovelace"
            text(host, ".usr-e") shouldBe "ada@example.com"
            host.textContent.orEmpty() shouldContain "Member"
        }

        test("what the server calls a role is not always what the page does") {
            roleLabel(adminUser(isRoot = true, role = "admin")) shouldBe "Owner"
            roleLabel(adminUser(role = "admin")) shouldBe "Admin"
            roleLabel(adminUser(role = "member")) shouldBe "Member"
        }

        test("both permissions are switches, and each reports its own toggle") {
            var edits = 0
            var shares = 0
            val host =
                userPage(
                    readyUser(adminUser(canEdit = true, canShare = false)),
                    onToggleCanEdit = { edits++ },
                    onToggleCanShare = { shares++ },
                )

            val switches = host.querySelectorAll(".sw-in").asList().filterIsInstance<HTMLInputElement>()
            switches.size shouldBe 2
            switches[0].hasAttribute("checked") shouldBe true
            switches[1].hasAttribute("checked") shouldBe false

            switches[0].click()
            switches[1].click()
            awaitFrame()

            edits shouldBe 1
            shares shouldBe 1
        }

        test("the owner's switches are genuinely disabled, and the page says why") {
            // ⛔ Not merely styled inert: the server refuses to change these, and a switch that
            // looks live and then reverts is worse than one that never moved.
            val host = userPage(readyUser(adminUser(isRoot = true)))

            host.querySelectorAll(".sw-in").asList().filterIsInstance<HTMLInputElement>().forEach {
                it.hasAttribute("disabled") shouldBe true
            }
            text(host, ".usr-note") shouldContain "owner"
        }

        test("a save in flight holds both switches still") {
            val host = userPage(readyUser(isSaving = true))

            host.querySelectorAll(".sw-in").asList().filterIsInstance<HTMLInputElement>().forEach {
                it.hasAttribute("disabled") shouldBe true
            }
        }

        test("a toggle the server refused says so without emptying the page") {
            val host = userPage(readyUser(error = InternalError(debugInfo = "boom")))

            text(host, ".usr-err").shouldNotBeNull()
            // Still a page about a person, not an error screen.
            text(host, ".usr-t") shouldBe "Ada Lovelace"
        }

        test("a member who could not be loaded is an error, not an empty shell") {
            val host = userPage(UserDetailUiState.Error(InternalError(debugInfo = "boom")))

            text(host, ".usr-none").shouldNotBeNull()
            host.querySelector(".sw-in").shouldBeNull()
        }

        test("the trail names the member once known, and the page until then") {
            userCrumb(UserDetailUiState.Loading) shouldBe "Member"
            userCrumb(readyUser(adminUser(displayName = "Ada Lovelace"))) shouldBe "Ada Lovelace"
            userCrumb(readyUser(adminUser(displayName = null, email = "ada@example.com"))) shouldBe "ada@example.com"
        }
    })
