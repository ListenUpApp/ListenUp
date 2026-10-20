package com.calypsan.listenup.client.share

import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.core.BookId

/**
 * The share link for [bookId], built from the connected server's own identity, or null when the server can't say who
 * it is. A link without that identity resolves only for someone already on the same server: the person who did not
 * need a link. Every client's Book Detail and Library menu use this one builder.
 */
suspend fun bookShareUrl(
    instanceRepository: InstanceRepository,
    bookId: BookId,
): String? {
    val info = instanceRepository.getServerInfoOrNull() ?: return null
    return ShareLinkCodec.encode(
        ShareTarget.Book(bookId = bookId, serverInstanceId = info.instanceId, serverUrl = info.remoteUrl?.trimEnd('/')),
    )
}
