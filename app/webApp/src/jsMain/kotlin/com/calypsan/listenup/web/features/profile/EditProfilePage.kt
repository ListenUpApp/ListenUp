package com.calypsan.listenup.web.features.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.profile.AvatarChange
import com.calypsan.listenup.client.presentation.profile.EditProfileUiState
import com.calypsan.listenup.client.presentation.profile.EditProfileViewModel
import com.calypsan.listenup.web.design.Field
import com.calypsan.listenup.web.design.PasswordField
import com.calypsan.listenup.web.design.FormSection
import com.calypsan.listenup.web.design.UserAvatar
import com.calypsan.listenup.web.readByteArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.AttrsScope
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.onSubmit
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.khronos.webgl.Int8Array
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.File

/**
 * Edit Profile — your picture, your name, your tagline, and your password, saved together.
 *
 * Pure in [state]: every value comes from the shared
 * [com.calypsan.listenup.client.presentation.profile.EditProfileViewModel] and every change leaves
 * through a callback, so a spec can drive any shape — mid-save, half-typed, mismatched passwords —
 * without a database behind it.
 *
 * **One Save for four different kinds of change.** The avatar is a REST upload, the name and
 * tagline are one RPC, and the password is a field on that same RPC — but the reader made one
 * decision, so they press one button. The ViewModel already sequences the parts and reports a
 * single outcome; splitting them into per-section saves here would expose plumbing as UI.
 *
 * [saveError] is what the ViewModel's `SaveFailed` said, held by the route because it arrives as a
 * one-shot event rather than as state. It is rendered inline rather than as a toast: two of the
 * three messages it can carry are about the password fields, and an error about a field belongs
 * beside the field, not in a corner that fades.
 */
@Composable
fun EditProfilePage(
    state: EditProfileUiState,
    onFirstName: (String) -> Unit,
    onLastName: (String) -> Unit,
    onTagline: (String) -> Unit,
    onCurrentPassword: (String) -> Unit,
    onNewPassword: (String) -> Unit,
    onConfirmPassword: (String) -> Unit,
    onPickAvatar: (ByteArray, String) -> Unit,
    onRemoveAvatar: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    saveError: String? = null,
) {
    Div(attrs = { classes("pedit") }) {
        H1(attrs = { classes("pedit-title") }) { Text("Edit profile") }

        when (state) {
            EditProfileUiState.Loading -> {
                Div(attrs = { classes("skel", "pedit-skel") })
            }

            is EditProfileUiState.Error -> {
                Div(attrs = { classes("empty") }) {
                    H3 { Text("This profile can't be edited") }
                    P { Text(state.message) }
                }
            }

            is EditProfileUiState.Ready -> {
                ReadyForm(
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
        }
    }
}

@Composable
private fun ReadyForm(
    state: EditProfileUiState.Ready,
    onFirstName: (String) -> Unit,
    onLastName: (String) -> Unit,
    onTagline: (String) -> Unit,
    onCurrentPassword: (String) -> Unit,
    onNewPassword: (String) -> Unit,
    onConfirmPassword: (String) -> Unit,
    onPickAvatar: (ByteArray, String) -> Unit,
    onRemoveAvatar: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    saveError: String?,
) {
    saveError?.let { message ->
        // Not dismissible, and it does not clear itself on the next keystroke: the reader needs it
        // still on screen while they fix the field it is about. Pressing Save again replaces it.
        Div(attrs = { classes("edit-error") }) {
            P(attrs = { attr("role", "alert") }) { Text(message) }
        }
    }

    // A real <form> over the sections AND the actions, so Enter in any field saves — the browser's
    // implicit submission needs the submit button inside the same form as the fields.
    // preventDefault stops the navigation that would reload the page and discard every edit on it.
    Form(attrs = {
        classes("edit-body")
        onSubmit { event ->
            event.preventDefault()
            onSave()
        }
    }) {
        FormSection(title = "Photo") {
            Hint("Shown wherever you appear — your profile, shelves you share, and the leaderboard.")
            PhotoField(state, onPickAvatar, onRemoveAvatar)
        }
        FormSection(title = "Name") {
            Hint("How you are listed to everyone else on this server.")
            Div(attrs = { classes("edit-grid") }) {
                Field(
                    label = "First name",
                    value = state.firstName,
                    onInput = onFirstName,
                    id = "pedit-first-name",
                    autocomplete = "given-name",
                )
                Field(
                    label = "Last name",
                    value = state.lastName,
                    onInput = onLastName,
                    id = "pedit-last-name",
                    autocomplete = "family-name",
                )
            }
        }
        FormSection(title = "Tagline") {
            Hint("A line about you, under your name on your profile.")
            Field(
                label = "Tagline",
                value = state.tagline,
                onInput = onTagline,
                id = "pedit-tagline",
            )
            // The ViewModel truncates at the limit, so this counts what is kept rather than
            // what was typed — a counter that ran past its own maximum would be describing
            // text the save is about to drop.
            Div(attrs = { classes("pedit-count") }) {
                Text("${state.tagline.length}/${EditProfileViewModel.MAX_TAGLINE_LENGTH}")
            }
        }
        FormSection(title = "Change password") {
            Hint("Leave these empty to keep the password you have.")
            PasswordField(
                label = "Current password",
                value = state.currentPassword,
                onInput = onCurrentPassword,
                id = "pedit-current-password",
                autocomplete = "current-password",
            )
            Div(attrs = { classes("edit-grid") }) {
                PasswordField(
                    label = "New password",
                    value = state.newPassword,
                    onInput = onNewPassword,
                    id = "pedit-new-password",
                    autocomplete = "new-password",
                )
                PasswordField(
                    label = "Confirm new password",
                    value = state.confirmPassword,
                    onInput = onConfirmPassword,
                    id = "pedit-confirm-password",
                    autocomplete = "new-password",
                )
            }
        }
        EditActions(state, onCancel)
    }
}

/** The sentence under a section title that says what the fields below it are for. */
@Composable
private fun Hint(text: String) {
    P(attrs = { classes("pedit-hint") }) { Text(text) }
}

/**
 * The avatar, and the two things you can do to it.
 *
 * What renders is the *pending* avatar, not the saved one — a staged upload previews from its own
 * bytes and a staged removal previews as the monogram — because the reader needs to see what Save
 * will produce, not what the server still has. The object URL is revoked when the pick changes, so
 * a reader who tries four pictures does not leak four blobs.
 *
 * "Remove photo" appears only when there is a photo to remove: [EditProfileUiState.Ready.hasImageAvatar]
 * is read from the same `public_profiles` row the avatar itself renders from, so the button cannot
 * disagree with the picture beside it.
 */
@Composable
private fun PhotoField(
    state: EditProfileUiState.Ready,
    onPickAvatar: (ByteArray, String) -> Unit,
    onRemoveAvatar: () -> Unit,
) {
    val staged = state.avatarChange as? AvatarChange.Upload
    val previewUrl =
        remember(staged) {
            staged?.let { URL.createObjectURL(Blob(arrayOf(it.bytes.unsafeCast<Int8Array>()))) }
        }
    DisposableEffect(previewUrl) {
        onDispose { previewUrl?.let(URL::revokeObjectURL) }
    }

    val scope = rememberCoroutineScope()
    var fileInput by remember { mutableStateOf<HTMLInputElement?>(null) }

    Div(attrs = { classes("pedit-photo") }) {
        if (previewUrl != null) {
            Img(src = previewUrl, alt = "New photo preview", attrs = { classes("pedit-preview") })
        } else {
            UserAvatar(
                userId = state.user.idString,
                name = state.user.displayName,
                size = AVATAR_SIZE,
                monogramOnly = state.avatarChange == AvatarChange.RevertToAuto,
            )
        }
        Div(attrs = { classes("pedit-photo-acts") }) {
            // ⛔ type=button on both. A <button> with no type defaults to SUBMIT, so inside the
            // form either of these would save the whole profile instead of touching the picture.
            Button(attrs = {
                classes("btn-o")
                attr("type", "button")
                disabledWhen(state.isSaving)
                onClick { fileInput?.click() }
            }) { Text("Upload photo") }
            if (state.hasImageAvatar && state.avatarChange !is AvatarChange.Upload) {
                Button(attrs = {
                    classes("btn-o")
                    attr("type", "button")
                    disabledWhen(state.isSaving || state.avatarChange == AvatarChange.RevertToAuto)
                    onClick { onRemoveAvatar() }
                }) { Text("Remove photo") }
            }
            Span(attrs = { classes("pedit-photo-hint") }) { Text("JPG, PNG or WebP") }
        }
        Input(type = InputType.File, attrs = {
            id("pedit-photo-input")
            attr("accept", "image/*")
            style { property("display", "none") }
            ref { element ->
                fileInput = element
                onDispose { fileInput = null }
            }
            onChange { event ->
                val element = event.target as HTMLInputElement
                element.files?.item(0)?.let { file -> stagePhoto(scope, file, onPickAvatar) }
                // Re-picking the same file must fire change again next time.
                element.value = ""
            }
        })
    }
}

/**
 * Read the picked file and stage its bytes on the ViewModel.
 *
 * The file's own MIME type goes with it: the server stores what it is told, and a picture sent as
 * the wrong type comes back as one the browser will not render.
 */
private fun stagePhoto(
    scope: CoroutineScope,
    file: File,
    onPickAvatar: (ByteArray, String) -> Unit,
) {
    scope.launch {
        val bytes = file.readByteArray() ?: return@launch
        onPickAvatar(bytes, file.type.ifBlank { DEFAULT_IMAGE_TYPE })
    }
}

/**
 * Cancel and Save.
 *
 * Save is disabled until something has actually changed, which is what makes it honest: an enabled
 * Save on an untouched form invites a press that fires three no-op requests and reports success for
 * a change nobody made. It is disabled again while a save is in flight and says so, for the reason
 * the same button on Book Edit is — the alternative is duplicate writes on the second press.
 *
 * Cancel does not ask "discard changes?". Neither native client asks, and inventing a confirmation
 * on web alone is the per-platform divergence that turns one product into three.
 */
@Composable
private fun EditActions(
    state: EditProfileUiState.Ready,
    onCancel: () -> Unit,
) {
    Div(attrs = { classes("edit-actions") }) {
        Button(attrs = {
            classes("btn-o")
            attr("type", "button")
            disabledWhen(state.isSaving)
            onClick { onCancel() }
        }) { Text("Cancel") }
        // No onClick: submitting the form is what saves, for click and Enter alike.
        Button(attrs = {
            classes("btn-c")
            attr("type", "submit")
            disabledWhen(state.isSaving || !state.isDirty)
        }) { Text(if (state.isSaving) "Saving…" else "Save changes") }
    }
}

/**
 * `disabled` is a boolean attribute: what makes a control disabled is the attribute being present,
 * not its value — so every site writes the same empty string, and this says it once.
 */
private fun AttrsScope<HTMLButtonElement>.disabledWhen(condition: Boolean) {
    if (condition) attr("disabled", "")
}

private const val AVATAR_SIZE = 96

/** What a file with no MIME type of its own is sent as; the server sniffs the bytes regardless. */
private const val DEFAULT_IMAGE_TYPE = "image/jpeg"
