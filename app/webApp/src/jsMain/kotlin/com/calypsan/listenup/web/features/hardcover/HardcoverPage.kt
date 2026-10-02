package com.calypsan.listenup.web.features.hardcover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.api.dto.hardcover.HardcoverBrokenReason
import com.calypsan.listenup.api.dto.hardcover.HardcoverHistory
import com.calypsan.listenup.api.dto.hardcover.HardcoverLinkFailure
import com.calypsan.listenup.api.dto.hardcover.HardcoverShareMode
import com.calypsan.listenup.api.dto.hardcover.HardcoverSyncProblem
import com.calypsan.listenup.client.presentation.hardcover.HardcoverBookToMatch
import com.calypsan.listenup.client.presentation.hardcover.HardcoverSyncStatus
import com.calypsan.listenup.client.presentation.settings.HardcoverSettingsUiState
import com.calypsan.listenup.client.util.formatDateLong
import com.calypsan.listenup.client.util.relativeLastActiveInSentence
import com.calypsan.listenup.web.copyToClipboard
import com.calypsan.listenup.web.design.Breadcrumb
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ButtonLink
import com.calypsan.listenup.web.design.ConfirmDialog
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.EmptyLook
import com.calypsan.listenup.web.design.EmptyState
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.LoadingState
import com.calypsan.listenup.web.design.PageHeader
import com.calypsan.listenup.web.design.Panel
import com.calypsan.listenup.web.design.SegmentItem
import com.calypsan.listenup.web.design.SegmentedControl
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.coverUrl
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.B
import org.jetbrains.compose.web.dom.Button as DomButton
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.Ol
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul

/**
 * Settings → Account → Hardcover: connect with Hardcover's device sign-in, watch it complete, see
 * who you are connected as, disconnect, and reconnect a broken connection.
 *
 * Pure in [state]; the session wiring — and the best-effort automatic open of the approval page —
 * lives one level up. Every string is the English text of en.json's `hardcover` group.
 *
 * ⛔ **The approval page is a real link, always.** The automatic open follows an awaited RPC, so a
 * browser's popup blocker is entitled to refuse it. The Linking phase therefore never depends on it:
 * "Open Hardcover in a new tab" is an `<a target="_blank">` a person clicks, which no blocker stops.
 *
 * ⛔ **Copying can fail without stranding anyone.** `navigator.clipboard` is missing on a plain-http
 * LAN server, which is most of these. The code is on screen and selectable whatever the clipboard
 * says; "Copied" appears only when the copy actually landed.
 *
 * Connected, the page is the approved sync canvas's two columns: who, the sync line and the books
 * that need a match on the left, with the earlier-books card between who and Sync (#1540); what is
 * shared and what comes back, and Disconnect, on the right.
 * A Sync now that failed is not drawn here — it is brief, so the route says it in a toast with
 * Try again while the sync line stays as it was; only a push or pull that is stuck gets a card.
 *
 * @param onSetShareMode Chooses when ListenUp updates Hardcover.
 * @param onSendHistory Sends the books finished before connecting.
 * @param onDismissHistory "Not now" on the earlier-books offer, or dismissing what the send came to.
 * @param onFindMatch Opens Find on Hardcover for one book of the Needs a match list.
 * @param onOpenKeptOff Opens the books kept off Hardcover (#1541).
 * @param nowMs What "Last synced …" measures against — read once per composition by the caller.
 * @param copyText Puts text on the clipboard and reports whether it got there. Specs replace it,
 *   because a headless browser's clipboard answers depend on permissions this page does not own.
 */
@Composable
fun HardcoverPage(
    state: HardcoverSettingsUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSyncNow: () -> Unit,
    onSetShareMode: (HardcoverShareMode) -> Unit,
    onSendHistory: () -> Unit,
    onDismissHistory: () -> Unit,
    onFindMatch: (bookId: String) -> Unit,
    onOpenKeptOff: () -> Unit,
    onOpenSettings: () -> Unit,
    nowMs: Long,
    copyText: (String, (Boolean) -> Unit) -> Unit = ::copyToClipboard,
) {
    Div(attrs = { classes("hc") }) {
        // Settings has no Account page of its own on web — Account is a section of Settings — so
        // both crumbs lead there. The trail still names the section the entry lives in.
        Breadcrumb(trail = listOf("Settings", "Account", SCREEN_TITLE), onNavigate = { onOpenSettings() })

        when (state) {
            HardcoverSettingsUiState.Loading -> {
                PageHeader(title = SCREEN_TITLE)
                LoadingState(label = "Checking your Hardcover connection…")
            }

            HardcoverSettingsUiState.NotOffered -> {
                PageHeader(title = SCREEN_TITLE)
                EmptyState(title = "Hardcover isn't set up on this server.", look = EmptyLook.Inline)
            }

            is HardcoverSettingsUiState.NotConnected -> {
                NotConnected(state, onConnect)
            }

            is HardcoverSettingsUiState.Linking -> {
                Linking(state, onCancel = onDisconnect, copyText = copyText)
            }

            is HardcoverSettingsUiState.Connected -> {
                Connected(
                    state,
                    nowMs,
                    onDisconnect,
                    onSyncNow,
                    onSetShareMode,
                    onSendHistory,
                    onDismissHistory,
                    onFindMatch,
                    onOpenKeptOff,
                )
            }

            is HardcoverSettingsUiState.Broken -> {
                Broken(state, onConnect, onDisconnect)
            }
        }
    }
}

@Composable
private fun NotConnected(
    state: HardcoverSettingsUiState.NotConnected,
    onConnect: () -> Unit,
) {
    Div(attrs = { classes("hc-cols") }) {
        Section(attrs = { classes(CARD, "hc-hero") }) {
            Span(attrs = {
                classes("hc-glyph")
                attr(ARIA_HIDDEN, "true")
            }) { Icon(WebIcon.Book, size = GLYPH_ICON) }
            // The hero's line is the page's title; the tab still says where you are.
            PageHeader(
                title = "Share what you finish",
                subtitle = "Connect your Hardcover account and ListenUp will mark what you listen to as read there.",
                documentTitle = SCREEN_TITLE,
            )
        }

        Section(attrs = { classes(CARD) }) {
            Ul(attrs = { classes("hc-list") }) {
                ListItem(WebIcon.Check, "A book you finish is marked as read on Hardcover.")
                ListItem(WebIcon.Lock, "You sign in on Hardcover itself. ListenUp never sees your password.")
            }
            state.lastFailure?.let { failure ->
                // Informational rather than alarming: the answer to every one is to connect again.
                P(attrs = {
                    classes("hc-note")
                    attr(ROLE, STATUS)
                }) { Text(failure.message()) }
            }
            Div(attrs = { classes("hc-actions") }) {
                Button(
                    kind = ButtonKind.Primary,
                    onClick = onConnect,
                    enabled = !state.isStarting,
                    attrs = { if (state.isStarting) attr("aria-busy", "true") },
                ) { Text("Connect Hardcover") }
            }
        }
    }
}

@Composable
private fun ListItem(
    icon: WebIcon,
    text: String,
) {
    Li(attrs = { classes("hc-item") }) {
        Span(attrs = {
            classes("hc-item-i")
            attr(ARIA_HIDDEN, "true")
        }) { Icon(icon, size = ITEM_ICON) }
        Span { Text(text) }
    }
}

/** A list item that says what is NOT shared, in the quiet style. */
@Composable
private fun QuietItem(
    icon: WebIcon,
    text: String,
) {
    Li(attrs = { classes("hc-item") }) {
        Span(attrs = {
            classes("hc-item-i")
            attr(ARIA_HIDDEN, "true")
        }) { Icon(icon, size = ITEM_ICON) }
        Span(attrs = { classes("hc-quiet") }) { Text(text) }
    }
}

/**
 * "Update Hardcover": As I listen, or Only when I finish (en.json's `hardcover.share_mode_*`), on the
 * shared [SegmentedControl] — a group named "Update Hardcover" whose buttons announce pressed or not.
 * The visible label is hidden from assistive tech, because the group already carries it as its name.
 * The choice shows at once; while it saves ([isSaving]) neither option takes a press.
 */
@Composable
private fun ShareModeChoice(
    mode: HardcoverShareMode,
    isSaving: Boolean,
    onSetShareMode: (HardcoverShareMode) -> Unit,
) {
    Div(attrs = { classes("hc-share-mode") }) {
        Span(attrs = {
            classes("hc-label")
            attr(ARIA_HIDDEN, "true")
        }) { Text(SHARE_MODE_LABEL) }
        SegmentedControl(
            items =
                listOf(
                    SegmentItem(HardcoverShareMode.AS_I_LISTEN.name, "As I listen"),
                    SegmentItem(HardcoverShareMode.FINISHED_ONLY.name, "Only when I finish"),
                ),
            active = mode.name,
            label = SHARE_MODE_LABEL,
            enabled = !isSaving,
            onSelect = { key -> HardcoverShareMode.entries.firstOrNull { it.name == key }?.let(onSetShareMode) },
        )
    }
}

@Composable
private fun Linking(
    state: HardcoverSettingsUiState.Linking,
    onCancel: () -> Unit,
    copyText: (String, (Boolean) -> Unit) -> Unit,
) {
    val address = state.verificationUri.withoutScheme()
    // Reset per code: a fresh code that reads "Copied" would claim a copy that never happened.
    var copied by remember(state.userCode) { mutableStateOf(false) }

    PageHeader(
        title = "Approve ListenUp on Hardcover",
        subtitle = "We opened the page for you. On another device, go to $address and enter this code.",
        documentTitle = SCREEN_TITLE,
    )

    Div(attrs = { classes("hc-cols") }) {
        Section(attrs = {
            classes(CARD, "hc-code-card")
            attr("aria-labelledby", CODE_HEADING_ID)
        }) {
            H2(attrs = {
                classes("hc-label")
                id(CODE_HEADING_ID)
            }) { Text("Your code") }
            Span(attrs = { classes("hc-code", "mono") }) { Text(state.userCode) }
            Button(
                kind = ButtonKind.Secondary,
                onClick = { copyText(state.userCode) { landed -> copied = landed } },
                attrs = { classes("hc-copy") },
            ) {
                if (copied) {
                    Icon(WebIcon.Check, size = ITEM_ICON)
                    Text("Copied")
                } else {
                    Text("Copy code")
                }
            }
        }

        Section(attrs = { classes(CARD) }) {
            Ol(attrs = { classes("hc-steps") }) {
                Step(1) {
                    Text("Open ")
                    B { Text(address) }
                }
                Step(2) { Text("Enter the code and approve ListenUp") }
                Step(STEP_THREE) { Text("This page updates on its own") }
            }
            ButtonLink(href = state.verificationUriComplete, kind = ButtonKind.Primary, attrs = {
                classes("hc-open")
                attr("target", "_blank")
                attr("rel", "noopener noreferrer")
            }) {
                Text("Open Hardcover in a new tab")
                Icon(WebIcon.ArrowRight, size = ITEM_ICON)
            }
        }
    }

    Div(attrs = {
        classes("hc-wait")
        attr(ROLE, STATUS)
        attr("aria-live", "polite")
    }) {
        Span(attrs = {
            classes("hc-spin")
            attr(ARIA_HIDDEN, "true")
        })
        Span(attrs = { classes("hc-wait-text") }) {
            B { Text("Waiting for you to approve") }
            Span { Text("This screen updates by itself. The code works for about 15 minutes.") }
        }
        // No confirmation: cancelling a code nobody has approved loses nothing.
        Button(kind = ButtonKind.Secondary, onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun Step(
    number: Int,
    content: @Composable () -> Unit,
) {
    Li(attrs = { classes("hc-step") }) {
        Span(attrs = {
            classes("hc-step-n")
            attr(ARIA_HIDDEN, "true")
        }) { Text(number.toString()) }
        Span { content() }
    }
}

@Composable
private fun Connected(
    state: HardcoverSettingsUiState.Connected,
    nowMs: Long,
    onDisconnect: () -> Unit,
    onSyncNow: () -> Unit,
    onSetShareMode: (HardcoverShareMode) -> Unit,
    onSendHistory: () -> Unit,
    onDismissHistory: () -> Unit,
    onFindMatch: (bookId: String) -> Unit,
    onOpenKeptOff: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }

    PageHeader(title = SCREEN_TITLE)
    Div(attrs = { classes("hc-cols", "hc-cols-top") }) {
        Div(attrs = { classes("hc-col") }) {
            Section(attrs = { classes(CARD, "hc-hero", "hc-who") }) {
                Span(attrs = {
                    classes("hc-avatar")
                    attr(ARIA_HIDDEN, "true")
                }) { Text(state.username.take(1).uppercase()) }
                Div(attrs = { classes("hc-who-text") }) {
                    Span(attrs = { classes("hc-badge") }) {
                        Icon(WebIcon.Check, size = BADGE_ICON)
                        Text("Connected")
                    }
                    H2(attrs = { classes("hc-user") }) { Text(state.username) }
                    Span(attrs = { classes("hc-since") }) { Text("Since ${formatDateLong(state.since)}") }
                }
            }
            // Canvas: the earlier-books card sits between who you are and Sync.
            HistoryCard(
                history = state.history,
                onSend = onSendHistory,
                onDismiss = onDismissHistory,
                onShowNeedsMatch = { document.getElementById(NEEDS_MATCH_ID)?.scrollIntoView() },
            )
            Panel(title = "Sync") {
                SyncBlock(lastSyncedAt = state.lastSyncedAt, sync = state.sync, nowMs = nowMs, onSyncNow = onSyncNow)
                val history = state.history
                if (history is HardcoverHistory.Available) EarlierBooksRow(books = history.bookCount, onSend = onSendHistory)
                if (state.keptOffBookCount > 0) KeptOffRow(books = state.keptOffBookCount, onOpen = onOpenKeptOff)
            }
            Div(attrs = {
                id(NEEDS_MATCH_ID)
                classes("hc-anchor")
            }) {
                NeedsMatch(books = state.booksToMatch, isKnown = state.isMatchListKnown, onFindMatch = onFindMatch)
            }
        }

        Div(attrs = { classes("hc-col") }) {
            Panel(title = "What ListenUp shares") {
                ShareModeChoice(mode = state.shareMode, isSaving = state.isSavingShareMode, onSetShareMode = onSetShareMode)
                Ul(attrs = { classes("hc-list") }) {
                    when (state.shareMode) {
                        HardcoverShareMode.AS_I_LISTEN -> {
                            ListItem(WebIcon.Book, "Books you start, as Currently reading")
                            ListItem(WebIcon.Headphones, "How far you've listened")
                            ListItem(WebIcon.Check, "Books you finish, marked as read")
                        }

                        HardcoverShareMode.FINISHED_ONLY -> {
                            ListItem(WebIcon.Check, "Only books you finish, marked as read, with when you started and finished")
                            QuietItem(WebIcon.Headphones, "Nothing is shared while you're still listening")
                        }
                    }
                }
                H3(attrs = { classes("hc-label", "hc-back-h") }) { Text("What comes back") }
                Ul(attrs = { classes("hc-list") }) {
                    Li(attrs = { classes("hc-item") }) {
                        Span(attrs = {
                            classes("hc-item-i")
                            attr(ARIA_HIDDEN, "true")
                        }) { Icon(WebIcon.Download, size = ITEM_ICON) }
                        Span {
                            Text("Books you've read elsewhere appear in Readers with a Hardcover label. ")
                            Span(attrs = { classes("hc-quiet") }) { Text("They never count as listening.") }
                        }
                    }
                    ListItem(WebIcon.Bookmark, "Your Want to Read list, on your To Read shelf")
                }
            }
            Div(attrs = { classes("hc-actions") }) {
                Button(
                    kind = ButtonKind.Secondary,
                    onClick = { confirming = true },
                    enabled = !state.isDisconnecting,
                ) { Text("Disconnect") }
            }
        }
    }

    DisconnectConfirm(open = confirming, onDisconnect = onDisconnect, onDismiss = { confirming = false })
}

/**
 * The sync line: when it last synced (or "Syncing…", announced as it starts) beside Sync now — or,
 * while a push or a pull is stuck, that said in plain words on a warning card, with Try again.
 *
 * A Sync now that failed draws as the plain line: it is brief, and the route's toast says it.
 * Sync now stays on screen while a sync runs, disabled and busy, so the line does not jump.
 */
@Composable
private fun SyncBlock(
    lastSyncedAt: Long?,
    sync: HardcoverSyncStatus,
    nowMs: Long,
    onSyncNow: () -> Unit,
) {
    val stuck = (sync as? HardcoverSyncStatus.Problem)?.problem?.takeIf { it != HardcoverSyncProblem.SYNC_NOW_FAILED }
    if (stuck != null) {
        Div(attrs = {
            classes("hc-problem")
            attr(ROLE, STATUS)
        }) {
            Span(attrs = {
                classes("hc-problem-i")
                attr(ARIA_HIDDEN, "true")
            }) { Icon(WebIcon.Alert, size = ITEM_ICON) }
            P { Text(stuck.words()) }
        }
        Div(attrs = { classes("hc-problem-act") }) {
            Button(kind = ButtonKind.Secondary, onClick = onSyncNow) {
                Icon(WebIcon.Refresh, size = BUTTON_ICON)
                Text("Try again")
            }
        }
        return
    }

    val syncing = sync == HardcoverSyncStatus.Syncing
    Div(attrs = { classes("hc-sync") }) {
        Span(attrs = {
            classes("hc-sync-i")
            attr(ARIA_HIDDEN, "true")
        }) {
            if (syncing) Span(attrs = { classes("hc-spin", "hc-spin-sm") }) else Icon(WebIcon.Clock, size = SYNC_ICON)
        }
        Span(attrs = {
            classes("hc-sync-t")
            attr(ROLE, STATUS)
        }) {
            Text(
                when {
                    syncing -> "Syncing…"
                    lastSyncedAt == null -> "Not synced yet"
                    else -> "Last synced ${relativeLastActiveInSentence(lastSyncedAt, nowMs)}"
                },
            )
        }
        Button(
            kind = ButtonKind.Secondary,
            onClick = onSyncNow,
            enabled = !syncing,
            attrs = { if (syncing) attr("aria-busy", "true") },
        ) {
            Icon(WebIcon.Refresh, size = BUTTON_ICON)
            Text("Sync now")
        }
    }
}

/**
 * "Kept off Hardcover · N books" (#1541): the quiet row under the sync line while any book is kept
 * off. A whole-row button, chevron and all, because it opens another page; en.json's
 * `hardcover.kept_off_title` and `kept_off_row_detail*`.
 */
@Composable
private fun KeptOffRow(
    books: Int,
    onOpen: () -> Unit,
) {
    DomButton(attrs = {
        classes("hc-sync", "hc-earlier", "hc-kept-row")
        attr("type", "button")
        onClick { onOpen() }
    }) {
        Span(attrs = {
            classes("hc-sync-i")
            attr(ARIA_HIDDEN, "true")
        }) { Icon(WebIcon.LinkOff, size = SYNC_ICON) }
        Span(attrs = { classes("hc-earlier-t") }) {
            Text("Kept off Hardcover")
            Span(attrs = { classes("hc-earlier-d") }) { Text(if (books == 1) "1 book" else "$books books") }
        }
        Span(attrs = {
            classes("hc-kept-chev")
            attr(ARIA_HIDDEN, "true")
        }) { Icon(WebIcon.ChevronRight, size = ITEM_ICON) }
    }
}

/**
 * The books ListenUp couldn't match, each with Find on Hardcover — or, once the server has said there
 * are none, one quiet line. Nothing at all while the list is not known: an unanswered read must not
 * claim that everything is matched.
 */
@Composable
private fun NeedsMatch(
    books: List<HardcoverBookToMatch>,
    isKnown: Boolean,
    onFindMatch: (bookId: String) -> Unit,
) {
    if (books.isEmpty()) {
        if (isKnown) {
            Panel(title = NEEDS_MATCH) {
                EmptyState(title = "Every book you've started is matched", look = EmptyLook.Inline)
            }
        }
        return
    }
    Panel(
        title = NEEDS_MATCH,
        flush = true,
        trailing = { Span(attrs = { classes("hc-count", "mono") }) { Text(books.size.toString()) } },
    ) {
        P(attrs = { classes("hc-match-lede") }) {
            Text("ListenUp couldn't tell which Hardcover book these are. Pick each one and it starts syncing.")
        }
        Ul(attrs = { classes("hc-match-list") }) {
            books.forEach { book ->
                val titleId = "hc-nm-${book.bookId}"
                Li(attrs = { classes("hc-match-row") }) {
                    Cover(
                        title = book.title,
                        imageUrl = coverUrl(book.bookId, book.coverHash, width = MATCH_COVER * 2),
                        size = MATCH_COVER,
                        decorative = true,
                    )
                    Div(attrs = { classes("hc-match-text") }) {
                        Span(attrs = {
                            classes("hc-match-title")
                            id(titleId)
                        }) { Text(book.title) }
                        if (book.authorNames.isNotBlank()) {
                            Span(attrs = { classes("hc-match-by") }) { Text(book.authorNames) }
                        }
                    }
                    // Every row's button reads the same, so it is described by its own book's title.
                    Button(
                        kind = ButtonKind.Secondary,
                        onClick = { onFindMatch(book.bookId) },
                        attrs = { attr("aria-describedby", titleId) },
                    ) {
                        Icon(WebIcon.Search, size = BUTTON_ICON)
                        Text("Find on Hardcover")
                    }
                }
            }
        }
    }
}

@Composable
private fun Broken(
    state: HardcoverSettingsUiState.Broken,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }

    PageHeader(title = SCREEN_TITLE)
    Section(attrs = { classes(CARD, "hc-broken") }) {
        Span(attrs = {
            classes("hc-glyph")
            attr(ARIA_HIDDEN, "true")
        }) { Icon(WebIcon.X, size = GLYPH_ICON) }
        Div(attrs = { classes("hc-broken-text") }) {
            H2(attrs = { classes("hc-card-h") }) { Text("Reconnect to keep sharing") }
            P(attrs = { classes(LEDE) }) { Text(state.reason.message()) }
            state.username?.let { username ->
                P(attrs = { classes(LEDE) }) {
                    Text("Was connected as ")
                    B { Text(username) }
                    Text(".")
                }
            }
            Div(attrs = { classes("hc-actions") }) {
                Button(
                    kind = ButtonKind.Primary,
                    onClick = onReconnect,
                    enabled = !state.isStarting,
                    attrs = { if (state.isStarting) attr("aria-busy", "true") },
                ) { Text("Reconnect") }
                Button(kind = ButtonKind.Secondary, onClick = { confirming = true }) { Text("Disconnect") }
            }
        }
    }

    DisconnectConfirm(open = confirming, onDisconnect = onDisconnect, onDismiss = { confirming = false })
}

/** The one question before a connection ends: web's shared confirm, never `window.confirm`. */
@Composable
private fun DisconnectConfirm(
    open: Boolean,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        open = open,
        title = "Disconnect Hardcover?",
        body = "ListenUp will stop updating your Hardcover account. Nothing already on Hardcover is removed.",
        confirmLabel = "Disconnect",
        onConfirm = {
            onDismiss()
            onDisconnect()
        },
        onDismiss = onDismiss,
    )
}

/**
 * A stuck sync in plain words — en.json's `hardcover.problem_*`. Deliberately no `else`: a new
 * problem must fail to compile here rather than borrow another's words.
 */
private fun HardcoverSyncProblem.words(): String =
    when (this) {
        HardcoverSyncProblem.SYNC_NOW_FAILED -> "Hardcover didn't answer just now. ListenUp will keep trying."
        HardcoverSyncProblem.PUSH_STALLED -> "Some of your listening hasn't reached Hardcover yet. ListenUp keeps trying."
        HardcoverSyncProblem.PULL_STALLED -> "ListenUp can't read your Hardcover shelf right now. It keeps trying."
    }

/** Why the last attempt ended — en.json's `hardcover.failure_*`. */
private fun HardcoverLinkFailure.message(): String =
    when (this) {
        HardcoverLinkFailure.DENIED -> "You declined on Hardcover. Connect again whenever you like."
        HardcoverLinkFailure.EXPIRED -> "The code expired before it was approved. Try again."
        HardcoverLinkFailure.UNREACHABLE -> "Hardcover couldn't be reached. Try again."
    }

/** Why a connection needs a reconnect — en.json's `hardcover.broken_*`. */
private fun HardcoverBrokenReason.message(): String =
    when (this) {
        HardcoverBrokenReason.REVOKED -> {
            "Hardcover no longer accepts ListenUp's access — it may have been removed on Hardcover. " +
                "Reconnect to continue."
        }

        HardcoverBrokenReason.CANNOT_DECRYPT -> {
            "This server can't read its saved Hardcover sign-in, which usually means it was restored " +
                "from a backup. Reconnect to continue."
        }

        HardcoverBrokenReason.MISSING_SCOPE -> {
            "ListenUp is missing a permission it needs on Hardcover. Reconnect to grant it."
        }
    }

/** `hardcover.app/link` — the address a person types, without the scheme nobody types. */
private fun String.withoutScheme(): String = substringAfter("://")

private const val ARIA_HIDDEN = "aria-hidden"
private const val ROLE = "role"
private const val STATUS = "status"
private const val CARD = "hc-card"
private const val LEDE = "hc-lede"

/** en.json's `hardcover.screen_title` — also the breadcrumb's last step. */
private const val SCREEN_TITLE = "Hardcover"
private const val CODE_HEADING_ID = "hc-code-h"
private const val STEP_THREE = 3
private const val GLYPH_ICON = 28
private const val ITEM_ICON = 18
private const val BADGE_ICON = 14
private const val BUTTON_ICON = 16
private const val SYNC_ICON = 22
private const val MATCH_COVER = 48

/** en.json's `hardcover.needs_match_section`. */
private const val NEEDS_MATCH = "Needs a match"

/** en.json's `hardcover.share_mode_label`. */
private const val SHARE_MODE_LABEL = "Update Hardcover"

/** Where Done's "need a match" scrolls to. */
private const val NEEDS_MATCH_ID = "hc-needs-match"
