package com.calypsan.listenup.server.mdns

import java.io.ByteArrayOutputStream

/**
 * Minimal DNS wire-format codec for advertise-only mDNS (RFC 1035 / 6762 / 6763).
 *
 * Encodes mDNS *responses* (we are a responder, not a resolver) and decodes only the *question*
 * section of inbound packets — enough to recognize a browse for our service type. Names are emitted
 * WITHOUT compression (legal for senders); inbound question names are parsed as length-prefixed labels
 * (compression pointers in questions are rare and treated as "not our query").
 */
object DnsCodec {
    /** RFC 1035 §3.1: each label is limited to 63 octets. */
    private const val MAX_LABEL_LENGTH = 63

    /** Encode a domain name as length-prefixed ASCII labels terminated by a zero byte. */
    fun encodeName(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (label in name.split('.')) {
            val bytes = label.encodeToByteArray()
            require(bytes.size <= MAX_LABEL_LENGTH) { "DNS label too long: $label" }
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        return out.toByteArray()
    }
}
