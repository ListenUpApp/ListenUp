package com.calypsan.listenup.web.features.admin

import com.calypsan.listenup.api.dto.auth.Permission
import com.calypsan.listenup.api.dto.auth.PermissionGroup
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.client.domain.model.AccessLabel
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.InviteInfo
import com.calypsan.listenup.client.domain.model.PermissionPreset
import com.calypsan.listenup.client.domain.model.UserPermissions
import com.calypsan.listenup.client.presentation.admin.CreateInviteErrorType
import com.calypsan.listenup.client.presentation.admin.CreateInviteField
import com.calypsan.listenup.client.presentation.admin.CreateInviteStatus
import com.calypsan.listenup.client.presentation.admin.CreateInviteUiState
import com.calypsan.listenup.client.presentation.admin.PermissionRow
import com.calypsan.listenup.client.presentation.admin.PermissionSection
import com.calypsan.listenup.client.presentation.admin.UserDetailUiState
import com.calypsan.listenup.client.presentation.admin.UserPermissionsUiState
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
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
    access: AccessLabel = AccessLabel.CONTRIBUTOR,
) = AdminUserInfo(
    id = id,
    email = email,
    displayName = displayName,
    firstName = null,
    lastName = null,
    isRoot = isRoot,
    role = role,
    status = status,
    permissions = UserPermissions(canEditMetadata = canEdit),
    createdAt = "2026-01-01T00:00:00Z",
    access = access,
)

internal fun readyUser(user: AdminUserInfo = adminUser()) = UserDetailUiState.Ready(user = user)

internal fun permissionsReady(
    role: UserRole = UserRole.MEMBER,
    flags: UserPermissions = UserPermissions(canEditMetadata = true),
    preset: PermissionPreset = PermissionPreset.CONTRIBUTOR,
    presetsShown: Boolean = true,
    curateWarning: Boolean = false,
    changeCount: Int = 0,
    user: AdminUserInfo = adminUser(displayName = "Quinn"),
    isSaving: Boolean = false,
    error: AppError? = null,
) = UserPermissionsUiState.Ready(
    user = user,
    role = role,
    flags = flags,
    sections =
        listOf(
            PermissionSection(
                PermissionGroup.LIBRARY,
                buildList {
                    add(PermissionRow(Permission.EDIT_METADATA, flags.canEditMetadata, isUnsaved = false))
                    if (presetsShown) {
                        add(PermissionRow(Permission.CURATE_LIBRARY, flags.canCurateLibrary, isUnsaved = curateWarning))
                    }
                },
            ),
        ),
    preset = preset,
    presetsShown = presetsShown,
    curateWarningShown = curateWarning,
    changeCount = changeCount,
    isProtected = user.isProtected,
    isConfirmingAdminPromotion = false,
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
            permissions: UserPermissionsUiState = permissionsReady(),
            actions: PermissionsPanelActions = PermissionsPanelActions(),
            onOpenAdmin: () -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                UserDetailPage(state = state, permissions = permissions, actions = actions, onOpenAdmin = onOpenAdmin)
            }

        fun switches(host: HTMLElement): List<HTMLInputElement> =
            host.querySelectorAll("[role=switch]").asList().filterIsInstance<HTMLInputElement>()

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
            host.querySelectorAll(".f-err, .inv-err").length shouldBe 1
            // …and it is the field's own message, so the input points at it.
            host.querySelector(".f-input[aria-describedby]") shouldNotBe null
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
            text(host, ".page-t") shouldBe "ada@example.com"
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

            text(host, ".page-t") shouldBe "Ada Lovelace"
            text(host, ".page-sub") shouldBe "ada@example.com"
            host.textContent.orEmpty() shouldContain "Member"
        }

        test("a member's permissions show the three presets as radio cards and both Library switches") {
            val host = userPage(readyUser(adminUser()))

            host.querySelectorAll("input[type=radio]").length shouldBe 3
            host.textContent.orEmpty() shouldContain "Listens and browses. Changes nothing."
            host.textContent.orEmpty() shouldContain "Edit metadata"
            host.textContent.orEmpty() shouldContain "Curate library"
            switches(host).size shouldBe 2
            // The preset the flags match is the checked card.
            (host.querySelectorAll("input[type=radio]").item(1) as HTMLInputElement).checked shouldBe true
        }

        test("a server enforcing Story World adds its group, with Contribute and Curate switches that reach the ViewModel") {
            val presses = mutableListOf<String>()
            val withStoryWorld =
                permissionsReady().let { ready ->
                    ready.copy(
                        sections =
                            ready.sections +
                                PermissionSection(
                                    PermissionGroup.STORY_WORLD,
                                    listOf(
                                        PermissionRow(Permission.CONTRIBUTE_STORY_WORLD, granted = true, isUnsaved = false),
                                        PermissionRow(Permission.CURATE_STORY_WORLD, granted = false, isUnsaved = false),
                                    ),
                                ),
                    )
                }
            val host =
                userPage(
                    readyUser(adminUser()),
                    permissions = withStoryWorld,
                    actions = PermissionsPanelActions(onSetPermission = { p, g -> presses += "set:$p=$g" }),
                )

            host.textContent.orEmpty() shouldContain "Story World"
            host.textContent.orEmpty() shouldContain "Add, edit and delete characters, places and events."
            host.textContent.orEmpty() shouldContain "Merge duplicate characters, places and events."
            switches(host).size shouldBe 4
            switches(host)[3].click()
            awaitFrame()

            presses shouldBe listOf("set:CURATE_STORY_WORLD=true")
        }

        test("choosing a preset and flipping a switch reach the ViewModel") {
            val presses = mutableListOf<String>()
            val host =
                userPage(
                    readyUser(adminUser()),
                    actions =
                        PermissionsPanelActions(
                            onSelectPreset = { presses += "preset:$it" },
                            onSetPermission = { p, g -> presses += "set:$p=$g" },
                        ),
                )

            (host.querySelectorAll("input[type=radio]").item(2) as HTMLInputElement).click()
            awaitFrame()
            switches(host)[1].click()
            awaitFrame()

            presses shouldBe listOf("preset:LIBRARIAN", "set:CURATE_LIBRARY=true")
        }

        test("an unsaved curate grant warns, tags the row Unsaved, and the save bar counts the change") {
            val host =
                userPage(
                    readyUser(adminUser()),
                    permissions =
                        permissionsReady(
                            flags = UserPermissions(canEditMetadata = true, canCurateLibrary = true),
                            preset = PermissionPreset.LIBRARIAN,
                            curateWarning = true,
                            changeCount = 1,
                        ),
                )

            host.textContent.orEmpty() shouldContain
                "Quinn will be able to merge and delete these for everyone on this server. Deletes can't be undone."
            host.textContent.orEmpty() shouldContain "Unsaved"
            host.textContent.orEmpty() shouldContain "1 unsaved change to Quinn's permissions"
        }

        test("Save and Discard reach the ViewModel, and nothing unsaved shows no bar") {
            val presses = mutableListOf<String>()
            val actions =
                PermissionsPanelActions(onDiscard = { presses += "discard" }, onSave = { presses += "save" })
            val host = userPage(readyUser(adminUser()), permissions = permissionsReady(changeCount = 2), actions = actions)

            host.textContent.orEmpty() shouldContain "2 unsaved changes to Quinn's permissions"
            button(host, "Discard").shouldNotBeNull().click()
            button(host, "Save changes").shouldNotBeNull().click()
            awaitFrame()
            presses shouldBe listOf("discard", "save")

            val clean = userPage(readyUser(adminUser()))
            clean.querySelector(".perm-bar").shouldBeNull()
        }

        test("the role picker asks for Admin, and the promotion is confirmed in a dialog") {
            val presses = mutableListOf<String>()
            val actions =
                PermissionsPanelActions(
                    onRequestRole = { presses += "role:$it" },
                    onConfirmAdminPromotion = { presses += "confirm" },
                )
            val host = userPage(readyUser(adminUser()), actions = actions)

            val role = host.querySelector(".perm-role select") as HTMLSelectElement
            role.value = "ADMIN"
            role.dispatchEvent(Event("change", org.w3c.dom.EventInit(bubbles = true)))
            awaitFrame()
            presses shouldBe listOf("role:ADMIN")

            val asking =
                userPage(
                    readyUser(adminUser()),
                    permissions = permissionsReady().copy(isConfirmingAdminPromotion = true),
                    actions = actions,
                )
            asking.textContent.orEmpty() shouldContain "Make Quinn an admin?"
            button(asking, "Make admin").shouldNotBeNull().click()
            awaitFrame()
            presses shouldBe listOf("role:ADMIN", "confirm")
        }

        test("an admin's page says admins can do everything, with no presets or switches") {
            val host = userPage(readyUser(adminUser()), permissions = permissionsReady(role = UserRole.ADMIN))

            host.textContent.orEmpty() shouldContain "Admins can do everything"
            switches(host).size shouldBe 0
            host.querySelectorAll("input[type=radio]").length shouldBe 0
        }

        test("an older server gets one switch, no presets, and the reason") {
            val host = userPage(readyUser(adminUser()), permissions = permissionsReady(presetsShown = false))

            switches(host).size shouldBe 1
            host.querySelectorAll("input[type=radio]").length shouldBe 0
            host.textContent.orEmpty() shouldContain
                "This server can only set this one permission. Update ListenUp on the server for the rest."
        }

        test("the owner's permissions can't be changed, and the page says why") {
            // ⛔ Not merely styled inert: the server refuses to change these, and a control that
            // looks live and then reverts is worse than one that never moved.
            val owner = adminUser(isRoot = true, role = "root")
            val host =
                userPage(readyUser(owner), permissions = permissionsReady(role = UserRole.ROOT, user = owner))

            text(host, ".usr-note") shouldContain "owner"
            host.querySelector(".perm-role select").shouldBeNull()
            switches(host).size shouldBe 0
        }

        test("a save in flight holds the switches and the presets still") {
            val host = userPage(readyUser(), permissions = permissionsReady(isSaving = true, changeCount = 1))

            switches(host).forEach { it.hasAttribute("disabled") shouldBe true }
            host.querySelectorAll("input[type=radio]").asList().filterIsInstance<HTMLInputElement>().forEach {
                it.hasAttribute("disabled") shouldBe true
            }
            button(host, "Saving…").shouldNotBeNull()
        }

        test("a save the server refused says so without emptying the page") {
            val host =
                userPage(readyUser(), permissions = permissionsReady(error = InternalError(debugInfo = "boom")))

            text(host, ".usr-err").shouldNotBeNull()
            // Still a page about a person, not an error screen.
            text(host, ".page-t") shouldBe "Ada Lovelace"
        }

        test("a member who could not be loaded is an error, not an empty shell") {
            val host = userPage(UserDetailUiState.Error(InternalError(debugInfo = "boom")))

            text(host, ".empty").shouldNotBeNull()
            host.querySelector("[role=switch]").shouldBeNull()
        }

        test("the trail names the member once known, and the page until then") {
            userCrumb(UserDetailUiState.Loading) shouldBe "Member"
            userCrumb(readyUser(adminUser(displayName = "Ada Lovelace"))) shouldBe "Ada Lovelace"
            userCrumb(readyUser(adminUser(displayName = null, email = "ada@example.com"))) shouldBe "ada@example.com"
        }
    })
