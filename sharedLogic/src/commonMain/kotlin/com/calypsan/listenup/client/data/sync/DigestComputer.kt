package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.DomainDigest
import org.kotlincrypto.hash.sha2.SHA256

/**
 * Computes a [DomainDigest] over a domain's local `(id, revision)` rows, byte-for-byte
 * identical to the server's `SyncableRepository.digest`: sort by id, join as `id|rev`
 * with `\n` separators plus a trailing `\n`, SHA-256, format `"sha256:"+lowercase-hex`.
 * Empty input → `count = 0, hash = ""`.
 */
internal object DigestComputer {
    fun compute(
        cursor: Long,
        rows: List<Pair<String, Long>>,
    ): DomainDigest {
        if (rows.isEmpty()) return DomainDigest(cursor = cursor, count = 0, hash = "")
        val joined =
            rows.sortedBy { it.first }.joinToString(separator = "\n") { (id, rev) -> "$id|$rev" } + "\n"
        val hex =
            SHA256().digest(joined.encodeToByteArray()).joinToString("") { b ->
                val v = b.toInt() and 0xFF
                v.toString(16).padStart(2, '0')
            }
        return DomainDigest(cursor = cursor, count = rows.size, hash = "sha256:$hex")
    }
}
