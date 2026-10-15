package com.calypsan.listenup.web.features.bookdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ContributorPhoto
import com.calypsan.listenup.web.design.DialogText
import com.calypsan.listenup.web.design.ModalDialog
import com.calypsan.listenup.web.design.avatarTintFor
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Button as DomButton

/**
 * Everyone credited on a book, each a way to their page: Android's "All credits" sheet and iOS's
 * Cast & Credits, which web had no equivalent of. Every row reads equally — a co-narrated book or an
 * anthology has no lead — with the person's framed photo in a circle (or their initials), their name
 * and their roles on this book.
 *
 * Choosing a person closes the list as it opens their page, as on both natives: the list was a way
 * to them, not a place to come back to.
 */
@Composable
internal fun CreditsDialog(
    credits: List<BookContributor>,
    onOpenContributor: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalDialog(open = true, title = "Credits", onDismiss = onDismiss, panelClass = "bd-credits") {
        DialogText(if (credits.size == 1) "1 contributor" else "${credits.size} contributors")
        Div(attrs = { classes("credit-list") }) {
            credits.forEach { credit ->
                key(credit.id) {
                    CreditRow(credit) {
                        onDismiss()
                        onOpenContributor(credit.id)
                    }
                }
            }
        }
        Div(attrs = { classes("dlg-actions") }) {
            Button(kind = ButtonKind.Secondary, onClick = { onDismiss() }) { Text("Done") }
        }
    }
}

@Composable
private fun CreditRow(
    credit: BookContributor,
    onOpen: () -> Unit,
) {
    DomButton(attrs = {
        classes("credit-row")
        attr("type", "button")
        onClick { onOpen() }
    }) {
        // Decorative: the name beside it is the row's name.
        Span(attrs = {
            classes("credit-photo")
            attr("aria-hidden", "true")
            style { property("background", avatarTintFor(credit.name)) }
        }) { ContributorPhoto(credit.id, credit.name, credit.imagePath, credit.imageFrame) }
        Span(attrs = { classes("credit-info") }) {
            Span(attrs = { classes("credit-name") }) { Text(credit.name) }
            rolesLine(credit.roles)?.let { line -> Span(attrs = { classes("credit-roles") }) { Text(line) } }
        }
    }
}

/** "Editor, Translator" from the roles' API tokens; null when the book records none. */
private fun rolesLine(roles: List<String>): String? =
    roles
        .map { role -> role.replaceFirstChar { it.uppercase() } }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(", ")
