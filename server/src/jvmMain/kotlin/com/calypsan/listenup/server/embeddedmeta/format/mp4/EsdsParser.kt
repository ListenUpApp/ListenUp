package com.calypsan.listenup.server.embeddedmeta.format.mp4

internal data class EsdsInfo(
    val objectTypeIndication: Int?,
    val avgBitrate: Int?,
    val audioSpecificConfig: ByteArray?,
)

private const val TAG_ES = 0x03
private const val TAG_DECODER_CONFIG = 0x04
private const val TAG_DECODER_SPECIFIC = 0x05

/** Parse an MP4 `esds` box payload (FullBox header + ES_Descriptor tree). Best-effort; null fields on absence. */
@Suppress("ReturnCount")
internal fun parseEsds(payload: ByteArray): EsdsInfo {
    var p = 4 // skip FullBox version+flags

    fun readLen(): Int { // expandable size: low 7 bits per byte, high bit = continue
        var size = 0
        repeat(4) {
            if (p >= payload.size) return size
            val b = payload[p++].toInt() and 0xFF
            size = (size shl 7) or (b and 0x7F)
            if (b and 0x80 == 0) return size
        }
        return size
    }

    if (p >= payload.size || (payload[p].toInt() and 0xFF) != TAG_ES) return EsdsInfo(null, null, null)
    p++
    readLen()
    p += 3 // ES_ID(2) + flags(1)
    if (p >= payload.size || (payload[p].toInt() and 0xFF) != TAG_DECODER_CONFIG) return EsdsInfo(null, null, null)
    p++
    readLen()
    if (p >= payload.size) return EsdsInfo(null, null, null)
    val oti = payload[p].toInt() and 0xFF
    p += 1
    p += 4 // streamType/upstream/reserved(1) + bufferSizeDB(3)
    p += 4 // maxBitrate
    if (p + 4 > payload.size) return EsdsInfo(oti, null, null)
    val avg =
        ((payload[p].toInt() and 0xFF) shl 24) or ((payload[p + 1].toInt() and 0xFF) shl 16) or
            ((payload[p + 2].toInt() and 0xFF) shl 8) or (payload[p + 3].toInt() and 0xFF)
    p += 4
    var asc: ByteArray? = null
    if (p < payload.size && (payload[p].toInt() and 0xFF) == TAG_DECODER_SPECIFIC) {
        p++
        val len = readLen()
        if (p + len <= payload.size) asc = payload.copyOfRange(p, p + len)
    }
    return EsdsInfo(objectTypeIndication = oti, avgBitrate = avg.takeIf { it > 0 }, audioSpecificConfig = asc)
}
