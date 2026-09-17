package com.calypsan.listenup.web.features.bookdetail

import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlin.js.Promise

/** How a share ended, so the page can say the right thing — or nothing. */
enum class ShareOutcome {
    /** The browser's own share sheet took it; it has already told the reader. */
    SHARED,

    /** No share sheet here, so the link went to the clipboard and the page must say so. */
    COPIED,

    /** Neither route was available, or both refused. */
    FAILED,
}

/**
 * Hands a book's share link to the browser.
 *
 * ⛔ Two routes, and which one runs is not a preference. `navigator.share` exists on mobile Safari
 * and Chrome Android but not on most desktop browsers, and it additionally requires a secure
 * context AND a user gesture — so it cannot be probed ahead of time and must be tried.
 *
 * The clipboard fallback is the established shape rather than an invention: the shared
 * `BookDetailPlatformActions.shareText` KDoc already reads "platform share sheet (Android) or
 * clipboard (Desktop)", so a desktop browser behaves as desktop Compose does.
 *
 * ⛔ An `AbortError` is the reader pressing Cancel on the sheet. That is a completed interaction,
 * not a failure, and must not fall through to copying a link they just declined to send.
 */
suspend fun shareBookLink(
    title: String,
    text: String,
    url: String,
): ShareOutcome {
    val nav = window.navigator.asDynamic()

    if (nav.share != null) {
        val payload = js("({})")
        payload.title = title
        payload.text = text
        payload.url = url
        val outcome =
            runCatching { (nav.share(payload) as Promise<*>).await() }
                .fold(onSuccess = { ShareOutcome.SHARED }, onFailure = { error ->
                    if (error.isCancellation()) ShareOutcome.SHARED else null
                })
        if (outcome != null) return outcome
    }

    if (nav.clipboard != null) {
        return runCatching { (nav.clipboard.writeText(url) as Promise<*>).await() }
            .fold(onSuccess = { ShareOutcome.COPIED }, onFailure = { ShareOutcome.FAILED })
    }
    return ShareOutcome.FAILED
}

/**
 * Whether a rejected share was the reader cancelling.
 *
 * Matched on the DOMException `name`, not the message: the message is localised by the browser and
 * would make this depend on the reader's language.
 */
private fun Throwable.isCancellation(): Boolean =
    this.asDynamic().name as? String == "AbortError" ||
        this.asDynamic().cause?.name as? String == "AbortError"
