package com.calypsan.listenup.web.features.contributoredit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.contributoredit.ContributorCandidate
import com.calypsan.listenup.client.presentation.contributoredit.ContributorEditUiEvent
import com.calypsan.listenup.client.presentation.contributoredit.ContributorEditUiState
import com.calypsan.listenup.web.design.ConfirmDialog
import com.calypsan.listenup.web.design.Field
import com.calypsan.listenup.web.design.FormSection
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.ModalDialog
import com.calypsan.listenup.web.design.TextAreaField
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.disabledWhen
import com.calypsan.listenup.web.readByteArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.onSubmit
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.khronos.webgl.Int8Array
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.File

/**
 * Contributor Edit — who this person is, what they are also called, and who they are really.
 *
 * Pure in [state]; the store wiring lives one level up. Every change leaves as a
 * [ContributorEditUiEvent], the same shape Book Edit uses.
 *
 * **Three different operations here can destroy a contributor, and each asks differently.**
 * Merging one *into* this person folds the other away and is reached from a deliberate picker.
 * Un-merging an alias splits it back out and is reversible by merging again. And a rename that
 * collides with an existing name is not a merge at all until the reader says so — the ViewModel
 * holds the rename back and this page asks which was meant. Sharing one confirmation across the
 * three would be asking a question none of them are.
 */
@Composable
fun ContributorEditPage(
    state: ContributorEditUiState,
    mergeCandidates: List<ContributorCandidate>,
    onEvent: (ContributorEditUiEvent) -> Unit,
    onMergeQuery: (String) -> Unit,
) {
    Div(attrs = { classes("ced") }) {
        if (state.isLoading) {
            Div(attrs = { classes("skel", "ced-skel") })
            return@Div
        }

        H1(attrs = { classes("ced-title") }) { Text(state.name.ifBlank { "Contributor" }) }

        state.error?.let { message ->
            Div(attrs = { classes("ced-err") }) {
                P(attrs = {
                    classes("ced-err-t")
                    attr("role", "alert")
                }) { Text(message) }
                Button(attrs = {
                    classes("ced-err-x")
                    attr(ATTR_TYPE, VALUE_BUTTON)
                    attr("aria-label", "Dismiss")
                    onClick { onEvent(ContributorEditUiEvent.DismissError) }
                }) { Icon(WebIcon.X, size = SMALL_ICON) }
            }
        }

        // A real <form> over the fields AND the actions, so Enter in any field saves — the
        // browser's implicit submission needs the submit button inside the same form.
        Form(attrs = {
            classes("edit-body")
            onSubmit { event ->
                event.preventDefault()
                onEvent(ContributorEditUiEvent.Save)
            }
        }) {
            FormSection(title = "Portrait") { PortraitField(state, onEvent) }
            FormSection(title = "Identity") { IdentityFields(state, onEvent) }
            FormSection(title = "Life") { LifeFields(state, onEvent) }
            FormSection(title = "Also known as") { AliasList(state, onEvent) }
            EditActions(state, onEvent)
        }

        if (state.mergeDialogVisible) {
            MergeDialog(
                query = state.mergeQuery,
                candidates = mergeCandidates,
                onQuery = onMergeQuery,
                onPick = { onEvent(ContributorEditUiEvent.MergeInto(it.id)) },
                onDismiss = { onEvent(ContributorEditUiEvent.MergeDialogDismissed) },
            )
        }

        // ⛔ The rename is NOT saved while this is up. The ViewModel holds it back precisely so the
        // reader answers first, and dismissing without answering keeps it held rather than
        // silently completing a rename that might have meant a merge.
        state.renameCollisionCandidate?.let { candidate ->
            RenameCollisionDialog(
                typedName = state.name,
                candidate = candidate,
                onMerge = { onEvent(ContributorEditUiEvent.ConfirmMergeOnRename) },
                onKeepSeparate = { onEvent(ContributorEditUiEvent.KeepSeparateOnRename) },
                onDismiss = { onEvent(ContributorEditUiEvent.DismissRenameCollision) },
            )
        }
    }
}

/**
 * The portrait, and the way to replace it.
 *
 * A staged pick previews from its own bytes rather than from the server — the upload does not
 * happen until Save, so asking the server for the image would show the old one right up until then.
 */
@Composable
private fun PortraitField(
    state: ContributorEditUiState,
    onEvent: (ContributorEditUiEvent) -> Unit,
) {
    val previewUrl =
        remember(state.pendingImageData) {
            state.pendingImageData?.let { bytes ->
                URL.createObjectURL(Blob(arrayOf(bytes.unsafeCast<Int8Array>())))
            }
        }
    DisposableEffect(previewUrl) { onDispose { previewUrl?.let(URL::revokeObjectURL) } }

    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf<HTMLInputElement?>(null) }
    var portraitFailed by remember(state.contributorId) { mutableStateOf(false) }

    Div(attrs = { classes("ced-portrait") }) {
        when {
            previewUrl != null -> {
                Img(src = previewUrl, alt = "New portrait preview", attrs = { classes(PHOTO) })
            }

            // A contributor with no photo is the normal case, so a 404 here is routine rather than
            // exceptional — the monogram it falls back to is a finished portrait.
            state.imagePath != null && !portraitFailed -> {
                Img(
                    src = contributorPhotoUrl(state.contributorId),
                    alt = state.name,
                    attrs = {
                        classes(PHOTO)
                        attr("decoding", "async")
                        addEventListener("error") { portraitFailed = true }
                    },
                )
            }

            else -> {
                Div(attrs = { classes(PHOTO, "ced-photo-none") }) { Icon(WebIcon.Person, size = PHOTO_ICON) }
            }
        }

        Div(attrs = { classes("ced-portrait-acts") }) {
            Button(attrs = {
                classes(BTN_SECONDARY)
                attr(ATTR_TYPE, VALUE_BUTTON)
                disabledWhen(state.isSaving || state.isUploadingImage)
                onClick { input?.click() }
            }) { Text(if (state.isUploadingImage) "Uploading…" else "Choose a photo") }
            Span(attrs = { classes("ced-portrait-hint") }) { Text("JPG, PNG or WebP") }
        }

        Input(type = InputType.File, attrs = {
            id("ced-photo-input")
            attr("accept", "image/*")
            style { property("display", "none") }
            ref { element ->
                input = element
                onDispose { input = null }
            }
            onChange { event ->
                val element = event.target as HTMLInputElement
                element.files?.item(0)?.let { file -> stagePhoto(scope, file, onEvent) }
                // Re-picking the same file must fire change again next time.
                element.value = ""
            }
        })
    }
}

@Composable
private fun IdentityFields(
    state: ContributorEditUiState,
    onEvent: (ContributorEditUiEvent) -> Unit,
) {
    Field(
        label = "Name",
        value = state.name,
        onInput = { onEvent(ContributorEditUiEvent.NameChanged(it)) },
        id = "ced-name",
    )
    TextAreaField(
        label = "Biography",
        value = state.descriptionText,
        onInput = { onEvent(ContributorEditUiEvent.DescriptionChanged(it)) },
        id = "ced-description",
    )
    Field(
        label = "Website",
        value = state.website,
        onInput = { onEvent(ContributorEditUiEvent.WebsiteChanged(it)) },
        placeholder = "https://example.com",
        id = "ced-website",
    )
}

/**
 * Born and died.
 *
 * `type=date` rather than a free-text field: the ViewModel wants ISO `YYYY-MM-DD`, and a date input
 * both produces exactly that and lets the reader use their own locale's calendar to get there.
 */
@Composable
private fun LifeFields(
    state: ContributorEditUiState,
    onEvent: (ContributorEditUiEvent) -> Unit,
) {
    Div(attrs = { classes("edit-grid") }) {
        Field(
            label = "Born",
            value = state.birthDate,
            onInput = { onEvent(ContributorEditUiEvent.BirthDateChanged(it)) },
            type = InputType.Date,
            id = "ced-birth",
        )
        Field(
            label = "Died",
            value = state.deathDate,
            onInput = { onEvent(ContributorEditUiEvent.DeathDateChanged(it)) },
            type = InputType.Date,
            id = "ced-death",
        )
    }
}

/**
 * The names this contributor is also filed under, and the way to add or split one.
 *
 * An alias is not a nickname the reader typed — it is another contributor that was folded in, which
 * is why adding one is a picker over real contributors and removing one gives that contributor
 * back rather than deleting a string.
 */
@Composable
private fun AliasList(
    state: ContributorEditUiState,
    onEvent: (ContributorEditUiEvent) -> Unit,
) {
    var splitting by remember { mutableStateOf<String?>(null) }

    P(attrs = { classes("ced-hint") }) {
        Text("Other spellings folded into this contributor. Splitting one out makes it a contributor again.")
    }
    if (state.aliases.isEmpty()) {
        P(attrs = { classes(NONE) }) { Text("No other names.") }
    } else {
        Div(attrs = { classes("ced-aliases") }) {
            state.aliases.forEach { alias ->
                Div(attrs = { classes("ced-alias") }) {
                    Span(attrs = { classes("ced-alias-n") }) { Text(alias) }
                    Button(attrs = {
                        classes(BTN_SECONDARY, "ced-split")
                        attr(ATTR_TYPE, VALUE_BUTTON)
                        attr("aria-label", "Split $alias back out")
                        disabledWhen(state.mergeInProgress)
                        onClick { splitting = alias }
                    }) { Text("Split out") }
                }
            }
        }
    }
    Div(attrs = { classes("ced-alias-act") }) {
        Button(attrs = {
            classes(BTN_SECONDARY, "ced-merge")
            attr(ATTR_TYPE, VALUE_BUTTON)
            disabledWhen(state.mergeInProgress)
            onClick { onEvent(ContributorEditUiEvent.MergeDialogOpened) }
        }) { Text(if (state.mergeInProgress) "Merging…" else "Fold another contributor in") }
    }

    val pending = splitting
    ConfirmDialog(
        open = pending != null,
        title = "Split this name back out?",
        body =
            "${pending ?: "This name"} becomes its own contributor again. The books filed under it " +
                "move with it. Nothing is deleted.",
        confirmLabel = "Split out",
        onConfirm = {
            pending?.let { onEvent(ContributorEditUiEvent.UnmergeAlias(it)) }
            splitting = null
        },
        onDismiss = { splitting = null },
    )
}

/** Pick a contributor to fold into this one. */
@Composable
private fun MergeDialog(
    query: String,
    candidates: List<ContributorCandidate>,
    onQuery: (String) -> Unit,
    onPick: (ContributorCandidate) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalDialog(open = true, title = "Fold a contributor in", onDismiss = onDismiss) {
        P(attrs = { classes("dlg-p") }) {
            Text(
                "The one you pick is folded into this contributor: its books move here and its name becomes another spelling.",
            )
        }
        Field(
            label = "Search contributors",
            value = query,
            onInput = onQuery,
            id = "ced-merge-query",
        )
        Div(attrs = { classes("ced-results") }) {
            when {
                query.isBlank() -> {
                    P(attrs = { classes(NONE) }) { Text("Type a name.") }
                }

                candidates.isEmpty() -> {
                    P(attrs = { classes(NONE) }) { Text("Nobody matched \"$query\".") }
                }

                else -> {
                    candidates.forEach { candidate ->
                        Button(attrs = {
                            classes("ced-result")
                            attr(ATTR_TYPE, VALUE_BUTTON)
                            onClick { onPick(candidate) }
                        }) {
                            // ⛔ Name only. `ContributorCandidate.bookCount` is a placeholder the
                            // ViewModel always fills with 0 — showing it would tell every reader
                            // that every contributor has no books. Compose omits it for the same
                            // reason; when a real count exists, both surfaces gain it together.
                            Span(attrs = { classes("ced-result-n") }) { Text(candidate.displayName) }
                        }
                    }
                }
            }
        }
        Div(attrs = { classes("dlg-actions") }) {
            Button(attrs = {
                classes("btn")
                attr(ATTR_TYPE, VALUE_BUTTON)
                onClick { onDismiss() }
            }) { Text("Cancel") }
        }
    }
}

/**
 * The typed name already belongs to someone.
 *
 * Three answers, not two: merge into them, keep both, or neither-yet. Dismissing leaves the rename
 * unsaved, which is why Cancel is not one of the two verbs — it is the absence of an answer.
 */
@Composable
private fun RenameCollisionDialog(
    typedName: String,
    candidate: ContributorCandidate,
    onMerge: () -> Unit,
    onKeepSeparate: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalDialog(open = true, title = "There is already a $typedName", onDismiss = onDismiss) {
        P(attrs = { classes("dlg-p") }) {
            Text(
                "${candidate.displayName} already exists. Fold this contributor into them, or keep " +
                    "the two separate under the same name?",
            )
        }
        Div(attrs = { classes("dlg-actions") }) {
            Button(attrs = {
                classes(BTN_SECONDARY)
                attr(ATTR_TYPE, VALUE_BUTTON)
                onClick { onKeepSeparate() }
            }) { Text("Keep separate") }
            Button(attrs = {
                classes("btn")
                attr(ATTR_TYPE, VALUE_BUTTON)
                onClick { onMerge() }
            }) { Text("Fold into ${candidate.displayName}") }
        }
    }
}

/**
 * Save and Cancel.
 *
 * Save is disabled until something changes, for the reason it is everywhere else: an enabled Save
 * on an untouched form invites a press that reports success for a change nobody made.
 */
@Composable
private fun EditActions(
    state: ContributorEditUiState,
    onEvent: (ContributorEditUiEvent) -> Unit,
) {
    Div(attrs = { classes("edit-actions") }) {
        // ⛔ type=button: a <button> with no type inside a form defaults to SUBMIT, so this would
        // save the very edits Cancel exists to discard.
        Button(attrs = {
            classes(BTN_SECONDARY)
            attr(ATTR_TYPE, VALUE_BUTTON)
            disabledWhen(state.isSaving)
            onClick { onEvent(ContributorEditUiEvent.Cancel) }
        }) { Text("Cancel") }
        // No onClick: submitting the form is what saves, for click and Enter alike.
        Button(attrs = {
            classes("btn-c")
            attr(ATTR_TYPE, "submit")
            disabledWhen(state.isSaving || !state.hasChanges)
        }) { Text(if (state.isSaving) "Saving…" else "Save changes") }
    }
}

private fun stagePhoto(
    scope: CoroutineScope,
    file: File,
    onEvent: (ContributorEditUiEvent) -> Unit,
) {
    scope.launch {
        val bytes = file.readByteArray() ?: return@launch
        onEvent(ContributorEditUiEvent.UploadImage(imageData = bytes, filename = file.name))
    }
}

/** Where the server serves a contributor's photo from. 404s for anyone who has none. */
internal fun contributorPhotoUrl(contributorId: String): String = "/api/v1/contributors/$contributorId/photo"

private const val ATTR_TYPE = "type"

private const val BTN_SECONDARY = "btn-o"

private const val PHOTO = "ced-photo"

private const val NONE = "ced-none"

private const val VALUE_BUTTON = "button"

private const val SMALL_ICON = 16

private const val PHOTO_ICON = 40
