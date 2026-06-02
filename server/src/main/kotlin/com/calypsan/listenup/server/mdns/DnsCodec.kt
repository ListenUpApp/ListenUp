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

    /** RFC 6763 §6.1: each TXT key=value string is limited to 255 octets. */
    private const val MAX_TXT_STRING = 255

    private const val TYPE_A = 1
    private const val TYPE_PTR = 12
    private const val TYPE_TXT = 16
    private const val TYPE_SRV = 33
    private const val CLASS_IN = 1

    /** Cache-flush bit on unique records (SRV/TXT/A) per RFC 6762 §11.3. */
    private const val FLUSH = 0x8000

    /** QR=1 (response) + AA=1 (authoritative) per RFC 1035 §4.1.1. */
    private const val FLAGS_RESPONSE = 0x8400

    /** Number of answer records emitted by [encodeResponse]: PTR, meta-PTR, SRV, TXT, A. */
    private const val ANSWER_COUNT = 5

    /** Mask to extract the low 8 bits of an integer when writing big-endian bytes. */
    private const val BYTE_MASK = 0xFF

    /** Bit-shift amounts for big-endian 32-bit encoding. */
    private const val SHIFT_24 = 24
    private const val SHIFT_16 = 16
    private const val SHIFT_8 = 8

    /** DNS wire-format header is always 12 bytes (RFC 1035 §4.1.1). */
    private const val HEADER_SIZE = 12

    /** Byte offset of the high octet of QDCOUNT in the DNS header. */
    private const val QDCOUNT_HI = 4

    /** Byte offset of the low octet of QDCOUNT in the DNS header. */
    private const val QDCOUNT_LO = 5

    /** High two bits set — marks a compression pointer per RFC 1035 §4.1.4. */
    private const val COMPRESSION_MASK = 0xC0

    /** Size in bytes of the QTYPE + QCLASS fields that follow each question name. */
    private const val QTYPE_QCLASS_SIZE = 4

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

    /**
     * Encode the full unsolicited-announcement / query-response packet for [service]:
     * PTR (service type → instance), meta-PTR (_services._dns-sd._udp → service type),
     * SRV (instance → host:port), TXT (instance → key=value pairs), A (host → [ipv4]).
     * [ttlSeconds] is 120 for announcements, 0 for a goodbye.
     */
    fun encodeResponse(
        service: MdnsServiceInfo,
        ipv4: ByteArray,
        ttlSeconds: Int,
    ): ByteArray {
        require(ipv4.size == 4) { "ipv4 must be 4 bytes" }
        val instanceFqdn = "${service.instanceName}.${MdnsServiceInfo.SERVICE_TYPE}"
        val hostFqdn = "${service.instanceName}.${MdnsServiceInfo.LOCAL}"

        val records = ByteArrayOutputStream()
        record(records, MdnsServiceInfo.SERVICE_TYPE, TYPE_PTR, CLASS_IN, ttlSeconds, encodeName(instanceFqdn))
        record(
            records,
            MdnsServiceInfo.META_QUERY,
            TYPE_PTR,
            CLASS_IN,
            ttlSeconds,
            encodeName(MdnsServiceInfo.SERVICE_TYPE),
        )
        val srv =
            ByteArrayOutputStream()
                .apply {
                    writeU16(this, 0) // priority
                    writeU16(this, 0) // weight
                    writeU16(this, service.port)
                    write(encodeName(hostFqdn))
                }.toByteArray()
        record(records, instanceFqdn, TYPE_SRV, CLASS_IN or FLUSH, ttlSeconds, srv)
        val txt =
            ByteArrayOutputStream()
                .apply {
                    if (service.txt.isEmpty()) {
                        write(0) // a single empty string per RFC 6763 §6.1 when no keys
                    } else {
                        for ((k, v) in service.txt) {
                            val kv = "$k=$v".encodeToByteArray()
                            require(kv.size <= MAX_TXT_STRING) { "TXT string too long: $k=$v" }
                            write(kv.size)
                            write(kv)
                        }
                    }
                }.toByteArray()
        record(records, instanceFqdn, TYPE_TXT, CLASS_IN or FLUSH, ttlSeconds, txt)
        record(records, hostFqdn, TYPE_A, CLASS_IN or FLUSH, ttlSeconds, ipv4)

        val out = ByteArrayOutputStream()
        writeU16(out, 0) // ID (0 for mDNS)
        writeU16(out, FLAGS_RESPONSE)
        writeU16(out, 0) // QDCOUNT
        writeU16(out, ANSWER_COUNT)
        writeU16(out, 0) // NSCOUNT
        writeU16(out, 0) // ARCOUNT
        out.write(records.toByteArray())
        return out.toByteArray()
    }

    /**
     * Parse the question section of an inbound packet and return the queried names (lowercased).
     * Returns an empty list on any malformed/short packet or a compression pointer in a question
     * (treated as "not for us"). We never throw on bad input — hostile/garbage multicast traffic
     * must not crash the responder.
     */
    fun questionNames(packet: ByteArray): List<String> {
        if (packet.size < HEADER_SIZE) return emptyList()
        val qdCount =
            (packet[QDCOUNT_HI].toInt() and BYTE_MASK) shl SHIFT_8 or
                (packet[QDCOUNT_LO].toInt() and BYTE_MASK)
        if (qdCount == 0) return emptyList()
        val names = mutableListOf<String>()
        var pos = HEADER_SIZE
        repeat(qdCount) {
            val labels = mutableListOf<String>()
            while (true) {
                if (pos >= packet.size) return names
                val len = packet[pos].toInt() and BYTE_MASK
                if (len == 0) {
                    pos += 1
                    break
                }
                if (len and COMPRESSION_MASK != 0) return names // compression pointer — bail, not our query
                if (pos + 1 + len > packet.size) return names
                labels += String(packet, pos + 1, len, Charsets.US_ASCII)
                pos += 1 + len
            }
            if (labels.isNotEmpty()) names += labels.joinToString(".").lowercase()
            pos += QTYPE_QCLASS_SIZE // skip QTYPE + QCLASS
        }
        return names
    }

    /** True if [packet] is a browse for our service type (or the DNS-SD meta-query). */
    fun isQueryForUs(packet: ByteArray): Boolean =
        questionNames(packet).any {
            it == MdnsServiceInfo.SERVICE_TYPE || it == MdnsServiceInfo.META_QUERY
        }

    private fun record(
        out: ByteArrayOutputStream,
        name: String,
        type: Int,
        klass: Int,
        ttlSeconds: Int,
        rdata: ByteArray,
    ) {
        out.write(encodeName(name))
        writeU16(out, type)
        writeU16(out, klass)
        writeU32(out, ttlSeconds.toLong())
        writeU16(out, rdata.size)
        out.write(rdata)
    }

    private fun writeU16(
        out: ByteArrayOutputStream,
        value: Int,
    ) {
        out.write((value ushr SHIFT_8) and BYTE_MASK)
        out.write(value and BYTE_MASK)
    }

    private fun writeU32(
        out: ByteArrayOutputStream,
        value: Long,
    ) {
        out.write(((value ushr SHIFT_24) and BYTE_MASK.toLong()).toInt())
        out.write(((value ushr SHIFT_16) and BYTE_MASK.toLong()).toInt())
        out.write(((value ushr SHIFT_8) and BYTE_MASK.toLong()).toInt())
        out.write((value and BYTE_MASK.toLong()).toInt())
    }
}
