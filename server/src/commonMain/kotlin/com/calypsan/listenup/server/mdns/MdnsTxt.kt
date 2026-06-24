package com.calypsan.listenup.server.mdns

import com.calypsan.listenup.server.api.ServerIdentity
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger("mdns.MdnsTxt")

/** RFC 6763 §6.1: each `key=value` TXT string is limited to 255 octets. */
private const val MAX_TXT_OCTETS = 255

/**
 * Builds the ordered mDNS TXT map advertised for this server.
 *
 * `id`/`name`/`version`/`api` are always present (`name` = the operator's [serverName]).
 * `remote` is added only when [remoteUrl] is non-blank AND the encoded `remote=<url>` string
 * fits the 255-octet TXT limit — an over-long value is dropped (with a warning) so it can never
 * make [DnsCodec]'s length `require` throw and kill announcements.
 */
fun buildMdnsTxt(
    instanceId: String,
    serverName: String,
    remoteUrl: String?,
): LinkedHashMap<String, String> {
    val txt =
        linkedMapOf(
            "id" to instanceId,
            "name" to serverName,
            "version" to ServerIdentity.VERSION,
            "api" to ServerIdentity.API_VERSION,
        )
    val remote = remoteUrl?.trim()
    if (!remote.isNullOrBlank()) {
        if ("remote=$remote".encodeToByteArray().size <= MAX_TXT_OCTETS) {
            txt["remote"] = remote
        } else {
            log.warn { "mDNS remote URL exceeds the 255-octet TXT limit; omitting remote= from advertisement" }
        }
    }
    return txt
}
