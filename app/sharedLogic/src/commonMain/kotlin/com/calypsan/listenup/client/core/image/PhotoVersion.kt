package com.calypsan.listenup.client.core.image

import org.kotlincrypto.hash.sha2.SHA256

/**
 * A photo's version: the lowercase SHA-256 hex of its bytes — the stem the server names a stored contributor photo with
 * (`contributors/<sha>.<ext>`), so a frame can be bound to a photo before it is uploaded.
 */
@OptIn(ExperimentalStdlibApi::class)
internal fun photoVersionOf(bytes: ByteArray): String = SHA256().digest(bytes).toHexString()
