package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import kotlinx.coroutines.delay
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
)

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
    ): Long {
        messages.lastOrNull()?.let { newest ->
            if (newest.text == text && newest.tone == tone) return newest.id
        }
        val id = nextId++
        messages = (messages + ToastMessage(id, text, tone)).takeLast(MAX_VISIBLE)
        return id
    }

    /** Removes the toast with [id]. A no-op if it has already gone — expiry and a click can race. */
    fun dismiss(id: Long) {
        messages = messages.filterNot { it.id == id }
    }
}

/**
 * Renders [queue] over the page, and retires each toast on a timer.
 *
 * The timer lives here rather than in [ToastQueue] so the queue stays a plain, testable object:
 * one effect per toast, keyed on its id, which cancels itself when the toast leaves for any other
 * reason. A single shared timer would have to reason about which toast it was counting down.
 */
@Composable
fun ToastHost(queue: ToastQueue) {
    if (queue.messages.isEmpty()) return

    Div(attrs = { classes("toastwrap") }) {
        queue.messages.forEach { message ->
            LaunchedEffect(message.id) {
                delay(TOAST_LIFETIME_MS)
                queue.dismiss(message.id)
            }

            Div(attrs = {
                classes("toast")
                // A failure is the only report the reader gets, so it interrupts; a notice waits
                // for a pause. `alert` and `status` carry their own aria-live semantics.
                attr("role", if (message.tone == ToastTone.Failure) "alert" else "status")
            }) {
                Div(attrs = {
                    classes("t-dot")
                    if (message.tone == ToastTone.Failure) classes("t-bad")
                }) {}
                Span { Text(message.text) }
                Span(attrs = {
                    classes("t-x")
                    attr("role", "button")
                    attr("aria-label", "Dismiss")
                    onClick { queue.dismiss(message.id) }
                }) {
                    Icon(WebIcon.X, size = DISMISS_ICON_SIZE)
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
 * Long enough to read a sentence twice, short enough not to sit over the page. Every toast is
 * also dismissible by hand — the timer is a convenience, not the only way out.
 */
private const val TOAST_LIFETIME_MS = 7_000L

/** How many toasts may stack before the oldest is retired. */
private const val MAX_VISIBLE = 3

private const val DISMISS_ICON_SIZE = 15
