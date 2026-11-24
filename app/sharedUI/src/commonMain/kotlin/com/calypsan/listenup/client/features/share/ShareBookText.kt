package com.calypsan.listenup.client.features.share

import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_share_book_text
import org.jetbrains.compose.resources.getString

/**
 * The text a shared book carries: the catalog's one sentence ("Check out <title> on ListenUp!"), a line break, then
 * the [url]. Every surface that shares a book builds its text here so the copy cannot drift.
 */
internal suspend fun shareBookText(
    title: String,
    url: String,
): String = getString(Res.string.common_share_book_text, title) + "\n" + url

/**
 * Shares a book: [share] receives [shareBookText] and the [url]. With no [url] — the server cannot name itself yet —
 * [onNoLink] runs instead, so a Share tap is never silent.
 */
internal suspend fun shareBookLink(
    url: String?,
    title: String,
    share: (text: String, url: String) -> Unit,
    onNoLink: suspend () -> Unit,
) {
    if (url == null) {
        onNoLink()
        return
    }
    share(shareBookText(title, url), url)
}
