package com.calypsan.listenup.web.features.profile

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.presentation.profile.AvatarChange
import com.calypsan.listenup.client.presentation.profile.EditProfileUiState
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.EventInit
import org.w3c.dom.asList
import org.w3c.dom.events.Event

private val hosts = mutableListOf<HTMLElement>()

private fun user(displayName: String = "Simon Hull") =
    User(
        id = UserId("u1"),
        email = "simon@example.com",
        displayName = displayName,
        isAdmin = false,
        createdAtMs = 0L,
        updatedAtMs = 0L,
    )

@Suppress("LongParameterList")
internal fun editing(
    firstName: String = "Simon",
    lastName: String = "Hull",
    tagline: String = "",
    currentPassword: String = "",
    newPassword: String = "",
    confirmPassword: String = "",
    avatarChange: AvatarChange = AvatarChange.None,
    hasImageAvatar: Boolean = false,
    isDirty: Boolean = false,
    isSaving: Boolean = false,
): EditProfileUiState.Ready =
    EditProfileUiState.Ready(
        user = user(),
        firstName = firstName,
        lastName = lastName,
        tagline = tagline,
        currentPassword = currentPassword,
        newPassword = newPassword,
        confirmPassword = confirmPassword,
        avatarChange = avatarChange,
        hasImageAvatar = hasImageAvatar,
        isDirty = isDirty,
        isSaving = isSaving,
    )

@Suppress("LongParameterList")
private fun page(
    state: EditProfileUiState,
    onFirstName: (String) -> Unit = {},
    onLastName: (String) -> Unit = {},
    onTagline: (String) -> Unit = {},
    onCurrentPassword: (String) -> Unit = {},
    onNewPassword: (String) -> Unit = {},
    onConfirmPassword: (String) -> Unit = {},
    onPickAvatar: (ByteArray, String) -> Unit = { _, _ -> },
    onRemoveAvatar: () -> Unit = {},
    onSave: () -> Unit = {},
    onCancel: () -> Unit = {},
    saveError: String? = null,
): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    hosts += host
    renderComposable(root = host) {
        EditProfilePage(
            state = state,
            onFirstName = onFirstName,
            onLastName = onLastName,
            onTagline = onTagline,
            onCurrentPassword = onCurrentPassword,
            onNewPassword = onNewPassword,
            onConfirmPassword = onConfirmPassword,
            onPickAvatar = onPickAvatar,
            onRemoveAvatar = onRemoveAvatar,
            onSave = onSave,
            onCancel = onCancel,
            saveError = saveError,
        )
    }
    return host
}

private fun input(
    host: HTMLElement,
    id: String,
) = host.querySelector("#$id") as HTMLInputElement

/** The button whose label is exactly [label]; null when nothing on the page says it. */
private fun button(
    host: HTMLElement,
    label: String,
): HTMLButtonElement? =
    host
        .querySelectorAll("button")
        .asList()
        .filterIsInstance<HTMLButtonElement>()
        .firstOrNull { it.textContent?.trim() == label }

private fun saveButton(host: HTMLElement) = host.querySelector(".edit-actions button[type=submit]") as HTMLButtonElement

/**
 * Edit Profile — the form over your own account.
 *
 * What these pin: the form reflects the ViewModel and nothing else, every field reports its own
 * changes (a mis-wired setter is the defect this shape invites), Save is honest about whether there
 * is anything to save, the photo row shows what Save will produce rather than what the server still
 * has, and a failed save is readable next to the field it is about.
 *
 * The password fields are pinned by their `autocomplete` values as well as their labels. Web is the
 * one platform with a password manager watching, and a form that does not declare itself is a form
 * that trains people to paste credentials by hand.
 */
class EditProfilePageTest :
    FunSpec({

        afterSpec {
            hosts.forEach { it.remove() }
            hosts.clear()
        }

        test("the form shows the name and tagline it was given") {
            val host = page(editing(firstName = "Ada", lastName = "Lovelace", tagline = "Counting on it."))

            input(host, "pedit-first-name").value shouldBe "Ada"
            input(host, "pedit-last-name").value shouldBe "Lovelace"
            input(host, "pedit-tagline").value shouldBe "Counting on it."
        }

        // ⛔ The tagline's length must match no other field's. This spec first shipped with a
        // 5-character tagline beside the 5-character default first name, and sabotage proved a
        // counter reading `firstName.length` passed it — the fixture, not the assertion, was
        // doing the work.
        test("the tagline counter counts the tagline, and counts what will be saved") {
            val host = page(editing(firstName = "Simon", lastName = "Hull", tagline = "Counting on it."))

            host.querySelector(".pedit-count")?.textContent shouldBe "15/60"
        }

        test("each field reports its own changes") {
            val seen = mutableMapOf<String, String>()
            val host =
                page(
                    editing(),
                    onFirstName = { seen["first"] = it },
                    onLastName = { seen["last"] = it },
                    onTagline = { seen["tagline"] = it },
                    onCurrentPassword = { seen["current"] = it },
                    onNewPassword = { seen["new"] = it },
                    onConfirmPassword = { seen["confirm"] = it },
                )

            // A distinct value per field, so a setter wired to the wrong input cannot read as right.
            listOf(
                "pedit-first-name" to "one",
                "pedit-last-name" to "two",
                "pedit-tagline" to "three",
                "pedit-current-password" to "four",
                "pedit-new-password" to "five",
                "pedit-confirm-password" to "six",
            ).forEach { (id, typed) ->
                val field = input(host, id)
                field.value = typed
                field.dispatchEvent(Event("input", EventInit(bubbles = true)))
            }
            awaitFrame()

            seen shouldBe
                mapOf(
                    "first" to "one",
                    "last" to "two",
                    "tagline" to "three",
                    "current" to "four",
                    "new" to "five",
                    "confirm" to "six",
                )
        }

        test("the password fields tell a password manager what they are") {
            val host = page(editing())

            input(host, "pedit-current-password").getAttribute("autocomplete") shouldBe "current-password"
            input(host, "pedit-new-password").getAttribute("autocomplete") shouldBe "new-password"
            input(host, "pedit-confirm-password").getAttribute("autocomplete") shouldBe "new-password"
        }

        test("Save is disabled until something changes") {
            val host = page(editing(isDirty = false))

            saveButton(host).hasAttribute("disabled") shouldBe true
        }

        test("Save is offered once something has changed") {
            val host = page(editing(isDirty = true))

            saveButton(host).hasAttribute("disabled") shouldBe false
        }

        test("Save says so while it is in flight, and cannot be pressed twice") {
            val host = page(editing(isDirty = true, isSaving = true))

            saveButton(host).textContent shouldBe "Saving…"
            saveButton(host).hasAttribute("disabled") shouldBe true
        }

        test("submitting the form saves") {
            var saved = 0
            val host = page(editing(isDirty = true), onSave = { saved++ })

            saveButton(host).click()
            awaitFrame()

            saved shouldBe 1
        }

        test("Cancel leaves without saving") {
            var saved = 0
            var cancelled = 0
            val host = page(editing(isDirty = true), onSave = { saved++ }, onCancel = { cancelled++ })

            // ⛔ A <button> with no type inside a <form> defaults to SUBMIT. If Cancel ever loses
            // its type=button it will save the very edits it exists to discard, and this is the
            // only thing that would notice.
            button(host, "Cancel").shouldNotBeNull().click()
            awaitFrame()

            cancelled shouldBe 1
            saved shouldBe 0
        }

        test("Remove photo is absent when there is no photo to remove") {
            val host = page(editing(hasImageAvatar = false))

            button(host, "Remove photo").shouldBeNull()
        }

        test("Remove photo is offered when there is one, and reports the press") {
            var removed = 0
            val host = page(editing(hasImageAvatar = true), onRemoveAvatar = { removed++ })

            button(host, "Remove photo").shouldNotBeNull().click()
            awaitFrame()

            removed shouldBe 1
        }

        test("a staged removal previews the monogram, not the picture still on the server") {
            val host = page(editing(hasImageAvatar = true, avatarChange = AvatarChange.RevertToAuto))

            // The monogram renders instead of an <img>; a request for the old avatar here would
            // show it right up until the save landed.
            host.querySelector(".pedit-photo img").shouldBeNull()
            host.querySelector(".pedit-photo .uav-mono").shouldNotBeNull()
        }

        test("a staged upload previews the picked picture") {
            val host =
                page(
                    editing(
                        hasImageAvatar = true,
                        avatarChange = AvatarChange.Upload(byteArrayOf(1, 2, 3), "image/png"),
                    ),
                )

            host.querySelector(".pedit-preview").shouldNotBeNull()
            // Removing is meaningless once a replacement is staged — Save would upload the new
            // picture and revert to initials in the same press, and only one of those can win.
            button(host, "Remove photo").shouldBeNull()
        }

        test("a failed save is reported on the form, and announced") {
            val host = page(editing(isDirty = true), saveError = "Passwords do not match.")

            val alert = host.querySelector(".edit-error p").shouldNotBeNull()
            alert.textContent shouldContain "Passwords do not match."
            alert.getAttribute("role") shouldBe "alert"
        }

        test("nothing has failed until something does") {
            val host = page(editing(isDirty = true))

            host.querySelector(".edit-error").shouldBeNull()
        }

        test("a profile still loading shows a skeleton rather than an empty form") {
            val host = page(EditProfileUiState.Loading)

            host.querySelector(".pedit-skel").shouldNotBeNull()
            host.querySelector("form").shouldBeNull()
        }

        test("a profile that cannot be loaded says so instead of offering fields") {
            val host = page(EditProfileUiState.Error("No user data available"))

            host.textContent shouldContain "No user data available"
            host.querySelector("form").shouldBeNull()
        }
    })
