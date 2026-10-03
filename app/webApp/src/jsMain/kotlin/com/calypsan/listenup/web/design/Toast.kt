package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import kotlinx.coroutines.delay
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/** What a toast is reporting. Drives the dot's colour and the element's ARIA role, nothing else. */
enum class ToastTone {
    /** Something happened that you asked for. Announced politely. */
    Notice,

    /** Something failed. Announced immediately, because nothing else on the page will say so. */
    Failure,
}

/**
 * One live toast.
 *
 * [id] rather than text is what dismisses and keys it: two failures can carry identical copy —
 * the `message` on an [AppError] is a per-subtype constant, so identical text is the *normal*
 * case for two of the same failure — and dismissing by text would take both.
 */
class ToastMessage(
    val id: Long,
    val text: String,
    val tone: ToastTone,
    val action: ToastAction? = null,
)

/**
 * The one thing a toast can offer to do — Undo after a one-tap pick, Try again after a sync that did
 * not land. Pressing it runs [onAction] and takes the toast away: the offer has been answered.
 */
class ToastAction(
    val label: String,
    val onAction: () -> Unit,
)

/** Shows a toast that carries a [ToastAction]: how a page reaches the shell's [ToastQueue] for one. */
typealias ShowActionToast = (text: String, tone: ToastTone, action: ToastAction) -> Unit

/**
 * The live toasts, and the rules for how they come and go.
 *
 * A plain state holder rather than a `Channel` or a `SharedFlow`: toasts are not events to be
 * delivered exactly once, they are a small list that is currently on screen, and everything
 * interesting about them ([MAX_VISIBLE], the duplicate rule) is a statement about that list.
 * Kept out of composition so those rules can be tested without a DOM.
 */
class ToastQueue {
    var messages: List<ToastMessage> by mutableStateOf(emptyList())
        private set

    private var nextId = 0L

    /**
     * Shows [text], and returns the id it was given — or the id of the toast already saying it.
     *
     * Two rules, both about not shouting. A repeated failure is common and boring: a retry loop,
     * or a sync that fails once per attempt, emits the same `AppError.message` over and over, and
     * three identical lines stacked up tells the reader nothing the first did not. And the stack
     * is capped, because a toast tower is just an error page with worse manners — past
     * [MAX_VISIBLE] the oldest goes, since the newest failure is the one still happening.
     */
    fun show(
        text: String,
        tone: ToastTone,
        action: ToastAction? = null,
    ): Long {
        messages.lastOrNull()?.let { newest ->
            if (newest.text == text && newest.tone == tone) return newest.id
        }
        val id = nextId++
        messages = (messages + ToastMessage(id, text, tone, action)).takeLast(MAX_VISIBLE)
        return id
    }

    /** Removes the toast with [id]. A no-op if it has already gone — expiry and a click can race. */
    fun dismiss(id: Long) {
        messages = messages.filterNot { it.id == id }
    }
}

/**
 * Renders [queue] over the page, and retires each notice on a timer.
 *
 * The stack is ONE polite live region, mounted before any toast arrives and never taken down. A
 * region inserted together with its words is announced unreliably — for `status`, often not at all —
 * so a notice is said because it is added to a region the screen reader is already watching. A
 * failure also carries `role="alert"`, the one insertion screen readers reliably interrupt for.
 *
 * Nothing that carries a [ToastAction] times out, and neither does a [ToastTone.Failure]. Undo after a
 * one-press pick is the only way back from it, and an offer that vanished after seven seconds was gone
 * before a keyboard reader on the next page could reach it, or a screen reader's queue got to it
 * (WCAG 2.2.1); the stack cap in [ToastQueue] is what keeps them from piling up instead. A plain
 * [ToastTone.Notice] only confirms what the reader just did, so it still retires itself — but not
 * while the pointer or keyboard focus is on it, since that is someone in the middle of reading it.
 *
 * The timer lives here rather than in [ToastQueue] so the queue stays a plain, testable object:
 * one effect per toast, keyed on its id and whether it is held, which cancels itself when the toast
 * leaves for any other reason. [noticeLifetimeMs] exists for specs; the app uses the default.
 */
@Composable
fun ToastHost(
    queue: ToastQueue,
    noticeLifetimeMs: Long = TOAST_LIFETIME_MS,
) {
    var held by remember { mutableStateOf(emptySet<Long>()) }

    Div(attrs = {
        classes("toastwrap")
        attr("aria-live", "polite")
    }) {
        queue.messages.forEach { message ->
            key(message.id) {
                if (message.tone == ToastTone.Notice && message.action == null) {
                    LaunchedEffect(message.id, message.id in held) {
                        if (message.id in held) return@LaunchedEffect
                        delay(noticeLifetimeMs)
                        queue.dismiss(message.id)
                    }
                }

                Div(attrs = {
                    classes("toast")
                    onMouseEnter { held = held + message.id }
                    onMouseLeave { held = held - message.id }
                    onFocusIn { held = held + message.id }
                    onFocusOut { held = held - message.id }
                    // A failure is the only report the reader gets, so it interrupts; a notice waits
                    // for a pause, said by the polite region it is added to.
                    if (message.tone == ToastTone.Failure) attr("role", "alert")
                }) {
                    Div(attrs = {
                        classes("t-dot")
                        if (message.tone == ToastTone.Failure) classes("t-bad")
                    }) {}
                    Span { Text(message.text) }
                    message.action?.let { action ->
                        Button(attrs = {
                            classes("t-act")
                            attr("type", "button")
                            onClick {
                                queue.dismiss(message.id)
                                action.onAction()
                            }
                        }) { Text(action.label) }
                    }
                    // A real button: this was a `<span role="button">` — announced as a button and then
                    // impossible to press from the keyboard, with no tab stop and no key handler.
                    Button(attrs = {
                        classes("t-x")
                        attr("type", "button")
                        attr("aria-label", "Dismiss notification")
                        onClick { queue.dismiss(message.id) }
                    }) {
                        Icon(WebIcon.X, size = DISMISS_ICON_SIZE)
                    }
                }
            }
        }
    }
}

/**
 * The line a toast shows for [this] error.
 *
 * [AppError.message] is the user-facing text and is normally exactly right — it is a body-level
 * constant, written to be shown. [AuthError.RateLimited] is the one subtype where that constant
 * has to omit something the reader needs: "Try again later" cannot say *how much* later, because
 * the wait is per-instance and the constant is not. `retryAfterSeconds` carries it, and this is
 * where it gets said. The Android and desktop snackbar makes the identical exception for the
 * identical reason.
 */
internal fun AppError.toastText(): String =
    if (this is AuthError.RateLimited) {
        "Too many attempts. Try again in ${retryAfterSeconds}s."
    } else {
        message
    }

/**
 * How long a toast stays.
 *
 * Long enough to read a sentence twice, short enough not to sit over the page. Only notices use it;
 * every toast is also dismissible by hand — the timer is a convenience, not the only way out.
 */
private const val TOAST_LIFETIME_MS = 7_000L

/** How many toasts may stack before the oldest is retired. */
private const val MAX_VISIBLE = 3

private const val DISMISS_ICON_SIZE = 15
