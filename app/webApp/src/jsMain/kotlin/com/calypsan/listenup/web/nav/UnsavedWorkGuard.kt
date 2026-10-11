package com.calypsan.listenup.web.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.calypsan.listenup.web.design.ConfirmDialog
import kotlinx.browser.window
import org.w3c.dom.events.Event

/**
 * The router the page is shown by, so a page holding unsaved work can register with it.
 *
 * Null where there is none (a spec that mounts one page): the guard still asks on the page's own
 * Cancel and still covers a closed tab, it just has no sidebar or Back button to intercept.
 */
val LocalRouter = staticCompositionLocalOf<Router?> { null }

/**
 * Asks before a page throws away a draft — the one shape every web edit surface shares.
 *
 * While [rememberUnsavedWorkGuard]'s `dirty` is true: a closed tab raises the browser's own prompt,
 * the router asks before any in-app link, breadcrumb or Back/Forward leaves the page, and the page's
 * own Cancel and back affordances go through [leave]. Clean, [leave] just leaves.
 *
 * Place [Dialog] once in the page, where its markup may sit anywhere; it renders nothing until asked.
 */
@Stable
class UnsavedWorkGuard internal constructor() {
    internal var dirty = false

    // Set the moment the reader chooses Discard, so the departure that follows is not asked about
    // again; cleared as soon as the draft changes.
    internal var discarded = false

    private var pending by mutableStateOf<(() -> Unit)?>(null)
    internal var onDiscard: () -> Unit = {}

    internal val isHeld: Boolean get() = dirty && !discarded
    internal val isAsking: Boolean get() = pending != null

    /** Runs [action] now when the draft is clean; otherwise asks first and runs it only on Discard. */
    fun leave(action: () -> Unit) {
        if (isHeld) pending = action else action()
    }

    internal fun ask(proceed: () -> Unit) {
        pending = proceed
    }

    /** The question. Discard runs the page's discard hook, then the departure that raised it. */
    @Composable
    fun Dialog() {
        val action = pending ?: return
        ConfirmDialog(
            open = true,
            title = "Discard your changes?",
            body = "The changes you made here will be lost.",
            confirmLabel = "Discard",
            onConfirm = {
                pending = null
                discarded = true
                onDiscard()
                action()
            },
            onDismiss = { pending = null },
        )
    }
}

/**
 * A guard for a page whose draft is [dirty]; [onDiscard] runs when the reader chooses to throw it
 * away, before the departure it was asked about (the chapter editor resets its session there).
 */
@Composable
fun rememberUnsavedWorkGuard(
    dirty: Boolean,
    onDiscard: () -> Unit = {},
): UnsavedWorkGuard {
    val router = LocalRouter.current
    val guard = remember { UnsavedWorkGuard() }
    // Read live by the router at the moment of leaving, so plain fields rather than snapshot state.
    if (guard.dirty != dirty) guard.discarded = false
    guard.dirty = dirty
    guard.onDiscard = onDiscard

    // The tab close, and anything else that leaves the page entirely. The browser shows its own
    // wording; all it takes from us is that there is something to lose.
    DisposableEffect(dirty) {
        val warn: (Event) -> Unit = { event -> event.preventDefault() }
        if (dirty) window.addEventListener(BEFORE_UNLOAD, warn)
        onDispose { window.removeEventListener(BEFORE_UNLOAD, warn) }
    }

    DisposableEffect(router, guard) {
        val release =
            router?.holdUnsavedWork(
                UnsavedWorkHold(isHeld = { guard.isHeld }, isAsking = { guard.isAsking }, ask = guard::ask),
            )
        onDispose { release?.invoke() }
    }
    return guard
}

/** The browser's own "you have unsaved work" prompt. */
private const val BEFORE_UNLOAD = "beforeunload"
