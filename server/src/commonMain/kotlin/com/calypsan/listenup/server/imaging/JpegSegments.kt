package com.calypsan.listenup.server.imaging

/** One colour component of a JPEG frame. */
internal class JpegComponent(
    val id: Int,
    val horizontalSampling: Int,
    val verticalSampling: Int,
    val quantTable: Int,
) {
    /** DC/AC entropy tables for the scan currently being read; reassigned per scan. */
    var dcTable: JpegHuffmanTable? = null
    var acTable: JpegHuffmanTable? = null

    /** Running DC predictor — DC is coded as a delta from the previous block of this component. */
    var dcPredictor: Int = 0

    /** Dequantised DC (and low-frequency AC) coefficients, one block-worth per block. */
    var coefficients: IntArray = IntArray(0)
    var blocksPerLine: Int = 0
    var blocksPerColumn: Int = 0
}

/** The frame header: SOF0 (baseline) or SOF2 (progressive). */
internal class JpegFrame(
    val progressive: Boolean,
    val width: Int,
    val height: Int,
    val components: List<JpegComponent>,
) {
    val maxHorizontalSampling: Int = components.maxOf { it.horizontalSampling }
    val maxVerticalSampling: Int = components.maxOf { it.verticalSampling }

    /** MCUs across and down — an MCU is one full sampling cycle of every component. */
    val mcusPerLine: Int = ceilDiv(width, maxHorizontalSampling * DCT_SIZE)
    val mcusPerColumn: Int = ceilDiv(height, maxVerticalSampling * DCT_SIZE)
}

/**
 * A canonical JPEG Huffman table, expanded for decoding.
 *
 * JPEG codes are canonical: all codes of a given length are consecutive. Rather than build a tree,
 * [minCode]/[maxCode]/[valuePointer] index by code length, which is the classic table-driven
 * formulation from the specification and avoids allocating a node per symbol.
 */
internal class JpegHuffmanTable(
    counts: IntArray,
    val values: IntArray,
) {
    val minCode = IntArray(MAX_CODE_LENGTH + 1)
    val maxCode = IntArray(MAX_CODE_LENGTH + 1) { -1 }
    val valuePointer = IntArray(MAX_CODE_LENGTH + 1)

    init {
        var code = 0
        var index = 0
        for (length in 1..MAX_CODE_LENGTH) {
            valuePointer[length] = index
            minCode[length] = code
            code += counts[length - 1]
            index += counts[length - 1]
            maxCode[length] = if (counts[length - 1] > 0) code - 1 else -1
            code = code shl 1
        }
    }
}

/**
 * Reads the marker segments preceding entropy-coded data.
 *
 * Returns null for anything this decoder does not handle — arithmetic coding, hierarchical or
 * lossless modes, 12-bit precision — rather than guessing, because a wrong guess here produces a
 * plausible-looking but wrong image, which is worse than no derivative at all.
 */
@Suppress("ReturnCount", "CyclomaticComplexMethod", "NestedBlockDepth", "LongMethod")
internal fun parseJpegSegments(bytes: ByteArray): JpegSegments? {
    if (bytes.size < MIN_JPEG_BYTES) return null
    if (readUShort(bytes, 0) != MARKER_SOI) return null

    val quantTables = arrayOfNulls<IntArray>(MAX_TABLES)
    val dcTables = arrayOfNulls<JpegHuffmanTable>(MAX_TABLES)
    val acTables = arrayOfNulls<JpegHuffmanTable>(MAX_TABLES)
    var frame: JpegFrame? = null
    var restartInterval = 0
    val scans = mutableListOf<JpegScan>()

    var offset = 2
    while (offset + 2 <= bytes.size) {
        if ((bytes[offset].toInt() and 0xFF) != MARKER_PREFIX) return null
        val marker = MARKER_PREFIX shl 8 or (bytes[offset + 1].toInt() and 0xFF)
        offset += 2

        if (marker == MARKER_EOI) break
        if (offset + 2 > bytes.size) return null
        val segmentLength = readUShort(bytes, offset)
        if (segmentLength < 2 || offset + segmentLength > bytes.size) return null
        val segmentStart = offset + 2
        val segmentEnd = offset + segmentLength

        when (marker) {
            MARKER_DQT -> {
                var at = segmentStart
                while (at < segmentEnd) {
                    val precision = (bytes[at].toInt() and 0xF0) shr 4
                    val id = bytes[at].toInt() and 0x0F
                    if (id >= MAX_TABLES) return null
                    at++
                    // 16-bit quantisation tables accompany 12-bit precision, which we do not decode.
                    if (precision != 0) return null
                    if (at + BLOCK_COEFFICIENTS > segmentEnd) return null
                    quantTables[id] = IntArray(BLOCK_COEFFICIENTS) { bytes[at + it].toInt() and 0xFF }
                    at += BLOCK_COEFFICIENTS
                }
            }

            MARKER_DHT -> {
                var at = segmentStart
                while (at < segmentEnd) {
                    val classAndId = bytes[at].toInt()
                    val tableClass = (classAndId and 0xF0) shr 4
                    val id = classAndId and 0x0F
                    if (id >= MAX_TABLES) return null
                    at++
                    if (at + MAX_CODE_LENGTH > segmentEnd) return null
                    val counts = IntArray(MAX_CODE_LENGTH) { bytes[at + it].toInt() and 0xFF }
                    at += MAX_CODE_LENGTH
                    val total = counts.sum()
                    if (at + total > segmentEnd) return null
                    val values = IntArray(total) { bytes[at + it].toInt() and 0xFF }
                    at += total
                    val table = JpegHuffmanTable(counts, values)
                    if (tableClass == 0) dcTables[id] = table else acTables[id] = table
                }
            }

            MARKER_DRI -> {
                restartInterval = readUShort(bytes, segmentStart)
            }

            MARKER_SOF0, MARKER_SOF1, MARKER_SOF2 -> {
                if (frame != null) return null
                val precision = bytes[segmentStart].toInt() and 0xFF
                if (precision != SUPPORTED_PRECISION) return null
                val height = readUShort(bytes, segmentStart + 1)
                val width = readUShort(bytes, segmentStart + 3)
                val componentCount = bytes[segmentStart + 5].toInt() and 0xFF
                if (width <= 0 || height <= 0 || componentCount == 0) return null
                if (componentCount != GREYSCALE_COMPONENTS && componentCount != YCBCR_COMPONENTS) return null

                val components =
                    (0 until componentCount).map { index ->
                        val at = segmentStart + 6 + index * 3
                        if (at + 2 >= bytes.size) return null
                        val sampling = bytes[at + 1].toInt() and 0xFF
                        JpegComponent(
                            id = bytes[at].toInt() and 0xFF,
                            horizontalSampling = (sampling and 0xF0) shr 4,
                            verticalSampling = sampling and 0x0F,
                            quantTable = bytes[at + 2].toInt() and 0xFF,
                        )
                    }
                if (components.any { it.horizontalSampling == 0 || it.verticalSampling == 0 }) return null
                frame = JpegFrame(marker == MARKER_SOF2, width, height, components)
            }

            MARKER_SOS -> {
                val current = frame ?: return null
                val scan = parseScanHeader(bytes, segmentStart, segmentEnd, current, dcTables, acTables) ?: return null
                // Entropy data runs from the end of the SOS header to the next marker that is not a
                // stuffed 0xFF00 or a restart marker.
                val dataStart = segmentEnd
                val dataEnd = findEntropyEnd(bytes, dataStart)
                scans += scan.copy(dataStart = dataStart, dataEnd = dataEnd)
                offset = dataEnd
                continue
            }

            // Arithmetic coding, hierarchical and lossless frames: not decoded, not guessed at.
            MARKER_SOF3, MARKER_SOF5, MARKER_SOF6, MARKER_SOF7, MARKER_SOF9, MARKER_SOF10,
            MARKER_SOF11, MARKER_SOF13, MARKER_SOF14, MARKER_SOF15,
            -> {
                return null
            }
        }

        offset = segmentEnd
    }

    val decoded = frame ?: return null
    if (scans.isEmpty()) return null
    return JpegSegments(decoded, quantTables, restartInterval, scans)
}

/** Everything the entropy stage needs, gathered from the marker segments. */
internal class JpegSegments(
    val frame: JpegFrame,
    val quantTables: Array<IntArray?>,
    val restartInterval: Int,
    val scans: List<JpegScan>,
)

/**
 * One SOS segment: which components it codes, the entropy tables it codes them with, and (for
 * progressive) which coefficients.
 *
 * The tables are **resolved here, not by id at decode time**, because a DHT redefines a table slot
 * for the scans that follow it — and a progressive file redefines them constantly. Keeping one
 * table per id and reading it after the whole file is parsed hands every scan the *last*
 * definition, which is how the chroma AC scans came to be decoded with a later scan's
 * end-of-band-only table and dropped all their coefficients.
 */
internal data class JpegScan(
    val componentIndices: List<Int>,
    val dcTables: List<JpegHuffmanTable?>,
    val acTables: List<JpegHuffmanTable?>,
    val spectralStart: Int,
    val spectralEnd: Int,
    val approximationHigh: Int,
    val approximationLow: Int,
    val dataStart: Int = 0,
    val dataEnd: Int = 0,
)

private fun parseScanHeader(
    bytes: ByteArray,
    start: Int,
    end: Int,
    frame: JpegFrame,
    dcTables: Array<JpegHuffmanTable?>,
    acTables: Array<JpegHuffmanTable?>,
): JpegScan? {
    val count = bytes[start].toInt() and 0xFF
    if (count == 0 || start + 1 + count * 2 + 3 > end) return null

    val indices = mutableListOf<Int>()
    val scanDcTables = mutableListOf<JpegHuffmanTable?>()
    val scanAcTables = mutableListOf<JpegHuffmanTable?>()
    for (i in 0 until count) {
        val at = start + 1 + i * 2
        val componentId = bytes[at].toInt() and 0xFF
        val index = frame.components.indexOfFirst { it.id == componentId }
        if (index < 0) return null
        indices += index
        // An id outside the table array, or one never defined, resolves to null and declines later
        // — a scan only touches the class of table its spectral band actually needs.
        scanDcTables += dcTables.getOrNull((bytes[at + 1].toInt() and 0xF0) shr 4)
        scanAcTables += acTables.getOrNull(bytes[at + 1].toInt() and 0x0F)
    }

    val tail = start + 1 + count * 2
    val approximation = bytes[tail + 2].toInt() and 0xFF
    return JpegScan(
        componentIndices = indices,
        dcTables = scanDcTables,
        acTables = scanAcTables,
        spectralStart = bytes[tail].toInt() and 0xFF,
        spectralEnd = bytes[tail + 1].toInt() and 0xFF,
        approximationHigh = (approximation and 0xF0) shr 4,
        approximationLow = approximation and 0x0F,
    )
}

/**
 * Finds where an entropy-coded segment ends.
 *
 * Inside entropy data a literal `0xFF` byte is stuffed as `0xFF00`, and restart markers
 * (`0xFFD0`–`0xFFD7`) are part of the stream. Any other `0xFF`-prefixed marker terminates it.
 */
private fun findEntropyEnd(
    bytes: ByteArray,
    from: Int,
): Int {
    var at = from
    while (at + 1 < bytes.size) {
        if ((bytes[at].toInt() and 0xFF) == MARKER_PREFIX) {
            val next = bytes[at + 1].toInt() and 0xFF
            val isStuffedByte = next == 0
            val isRestart = next in RESTART_MARKER_LOW..RESTART_MARKER_HIGH
            if (!isStuffedByte && !isRestart) return at
        }
        at++
    }
    return bytes.size
}

internal fun readUShort(
    bytes: ByteArray,
    at: Int,
): Int = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

internal fun ceilDiv(
    value: Int,
    divisor: Int,
): Int = (value + divisor - 1) / divisor

internal const val DCT_SIZE = 8
internal const val BLOCK_COEFFICIENTS = 64
internal const val MAX_CODE_LENGTH = 16
internal const val MARKER_PREFIX = 0xFF
internal const val RESTART_MARKER_LOW = 0xD0
internal const val RESTART_MARKER_HIGH = 0xD7

private const val MIN_JPEG_BYTES = 4
private const val MAX_TABLES = 4
private const val SUPPORTED_PRECISION = 8
private const val GREYSCALE_COMPONENTS = 1
private const val YCBCR_COMPONENTS = 3
private const val MARKER_SOI = 0xFFD8
private const val MARKER_EOI = 0xFFD9
private const val MARKER_DQT = 0xFFDB
private const val MARKER_DHT = 0xFFC4
private const val MARKER_DRI = 0xFFDD
private const val MARKER_SOS = 0xFFDA
private const val MARKER_SOF0 = 0xFFC0
private const val MARKER_SOF1 = 0xFFC1
private const val MARKER_SOF2 = 0xFFC2
private const val MARKER_SOF3 = 0xFFC3
private const val MARKER_SOF5 = 0xFFC5
private const val MARKER_SOF6 = 0xFFC6
private const val MARKER_SOF7 = 0xFFC7
private const val MARKER_SOF9 = 0xFFC9
private const val MARKER_SOF10 = 0xFFCA
private const val MARKER_SOF11 = 0xFFCB
private const val MARKER_SOF13 = 0xFFCD
private const val MARKER_SOF14 = 0xFFCE
private const val MARKER_SOF15 = 0xFFCF
