package com.calypsan.listenup.server.imaging

/**
 * Decodes a JPEG at a **reduced scale**, or returns `null` if it cannot.
 *
 * This is a derivative decoder, not a general one. We are turning a 2400x2400 cover into a ~400px
 * tile, and JPEG's DCT is already a frequency decomposition — so instead of reconstructing 5.8M
 * pixels and discarding 97% of them, we reconstruct only the low-frequency coefficients that
 * survive the downscale:
 *
 * - **1/8** — the DC coefficient of each 8x8 block *is* that block's average, so this is a clean
 *   box downscale with no inverse transform at all.
 * - **1/4** — the top-left 2x2 coefficients through a 2x2 inverse transform.
 *
 * The scale is chosen as the smallest reduction whose width still covers [maxWidth], so the caller
 * can resize down precisely afterwards. If no supported reduction reaches [maxWidth] — meaning the
 * caller wants something near full size — this declines, because for that the original bytes are
 * the right answer.
 *
 * **Progressive rides along.** A progressive JPEG's first scan is its DC scan, so decoding DC-first
 * runs with the format rather than against it: we read the DC scans and the low AC bands we need,
 * and ignore the rest. That is what makes half of a real cover library decodable at all — 589 of
 * 1180 covers measured are progressive.
 */
@Suppress("ReturnCount", "TooGenericExceptionCaught")
internal fun decodeJpeg(
    bytes: ByteArray,
    maxWidth: Int,
): PixelBuffer? {
    require(maxWidth > 0) { "maxWidth must be positive, got $maxWidth" }
    return try {
        val segments = parseJpegSegments(bytes) ?: return null
        val frame = segments.frame

        // Smallest reduction whose result still covers maxWidth. Ordered coarsest-first so an
        // over-large request falls off the end and declines rather than silently returning DC-only.
        val reduction =
            SUPPORTED_REDUCTIONS
                .filter { ceilDiv(frame.width, it) >= maxWidth }
                .maxOrNull() ?: return null

        allocateCoefficients(frame)
        for (scan in segments.scans) {
            decodeScan(bytes, segments, scan) ?: return null
        }
        reconstruct(segments, reduction)
    } catch (_: Exception) {
        // Truncated, corrupt, or a stream that disagrees with its own header — one answer for all.
        null
    }
}

private fun allocateCoefficients(frame: JpegFrame) {
    for (component in frame.components) {
        // Allocated in whole MCUs: an interleaved scan writes padding blocks past the image edge,
        // and sizing to the image alone would overflow on any non-multiple-of-MCU dimension.
        component.blocksPerLine = frame.mcusPerLine * component.horizontalSampling
        component.blocksPerColumn = frame.mcusPerColumn * component.verticalSampling
        component.coefficients = IntArray(component.blocksPerLine * component.blocksPerColumn * BLOCK_COEFFICIENTS)
        component.dcPredictor = 0
    }
}

/** Reads one entropy-coded scan into the components' coefficient arrays. */
@Suppress("ReturnCount")
private fun decodeScan(
    bytes: ByteArray,
    segments: JpegSegments,
    scan: JpegScan,
): Unit? {
    val frame = segments.frame
    val reader = JpegBitReader(bytes, scan.dataStart, scan.dataEnd)
    val components = scan.componentIndices.map { frame.components[it] }

    components.forEachIndexed { index, component ->
        component.dcTable = scan.dcTables[index]
        component.acTable = scan.acTables[index]
        component.dcPredictor = 0
    }

    val singleComponent = components.size == 1
    // A non-interleaved scan walks that component's own block grid; an interleaved one walks MCUs.
    val rows = if (singleComponent) ceilDiv(componentHeightInBlocks(frame, components[0]), 1) else frame.mcusPerColumn
    val columns = if (singleComponent) componentWidthInBlocks(frame, components[0]) else frame.mcusPerLine

    var sinceRestart = 0
    for (row in 0 until rows) {
        for (column in 0 until columns) {
            if (segments.restartInterval > 0 && sinceRestart == segments.restartInterval) {
                reader.alignToRestart()
                components.forEach { it.dcPredictor = 0 }
                sinceRestart = 0
            }
            sinceRestart++

            if (singleComponent) {
                val component = components[0]
                decodeBlock(reader, segments, scan, component, row, column) ?: return null
            } else {
                for (component in components) {
                    for (v in 0 until component.verticalSampling) {
                        for (h in 0 until component.horizontalSampling) {
                            val blockRow = row * component.verticalSampling + v
                            val blockColumn = column * component.horizontalSampling + h
                            decodeBlock(reader, segments, scan, component, blockRow, blockColumn) ?: return null
                        }
                    }
                }
            }
        }
    }
    return Unit
}

private fun componentWidthInBlocks(
    frame: JpegFrame,
    component: JpegComponent,
): Int = ceilDiv(frame.width * component.horizontalSampling, frame.maxHorizontalSampling * DCT_SIZE)

private fun componentHeightInBlocks(
    frame: JpegFrame,
    component: JpegComponent,
): Int = ceilDiv(frame.height * component.verticalSampling, frame.maxVerticalSampling * DCT_SIZE)

/**
 * Decodes one 8x8 block's contribution from the current scan.
 *
 * Baseline codes every coefficient in one pass. Progressive splits them across scans by spectral
 * band and bit position, so a block is built up over several visits — which is why the coefficient
 * array is accumulated into rather than assigned.
 */
@Suppress("ReturnCount")
private fun decodeBlock(
    reader: JpegBitReader,
    segments: JpegSegments,
    scan: JpegScan,
    component: JpegComponent,
    blockRow: Int,
    blockColumn: Int,
): Unit? {
    if (blockRow >= component.blocksPerColumn || blockColumn >= component.blocksPerLine) return Unit
    val base = (blockRow * component.blocksPerLine + blockColumn) * BLOCK_COEFFICIENTS
    val coefficients = component.coefficients

    if (!segments.frame.progressive) {
        val dcTable = component.dcTable ?: return null
        val acTable = component.acTable ?: return null
        val magnitude = reader.decodeHuffman(dcTable) ?: return null
        val diff = if (magnitude == 0) 0 else reader.receiveExtend(magnitude)
        component.dcPredictor += diff
        coefficients[base] = component.dcPredictor

        // AC coefficients are read to keep the bitstream in sync, but only the low-frequency ones
        // are retained — the rest cannot survive the downscale and storing them would be waste.
        var index = 1
        while (index < BLOCK_COEFFICIENTS) {
            val symbol = reader.decodeHuffman(acTable) ?: return null
            val run = (symbol and 0xF0) shr 4
            val size = symbol and 0x0F
            if (size == 0) {
                if (run != ZERO_RUN_16) break
                index += ZERO_RUN_16
                continue
            }
            index += run
            if (index >= BLOCK_COEFFICIENTS) break
            val value = reader.receiveExtend(size)
            if (ZIGZAG[index] < RETAINED_COEFFICIENTS) coefficients[base + ZIGZAG[index]] = value
            index++
        }
        return Unit
    }

    return decodeProgressiveBlock(reader, scan, component, base)
}

/**
 * Progressive: the DC scans, and the AC bands low enough to matter at our scales.
 *
 * Higher AC bands are skipped wholesale — not decoded and discarded, but never entered — which is
 * why progressive costs *less* here than baseline rather than more.
 */
@Suppress("ReturnCount")
private fun decodeProgressiveBlock(
    reader: JpegBitReader,
    scan: JpegScan,
    component: JpegComponent,
    base: Int,
): Unit? {
    val coefficients = component.coefficients

    if (scan.spectralStart == 0) {
        // DC scan — first pass codes a delta, refinement passes append one bit.
        if (scan.approximationHigh == 0) {
            val dcTable = component.dcTable ?: return null
            val magnitude = reader.decodeHuffman(dcTable) ?: return null
            val diff = if (magnitude == 0) 0 else reader.receiveExtend(magnitude)
            component.dcPredictor += diff
            coefficients[base] = component.dcPredictor shl scan.approximationLow
        } else {
            if (reader.readBit() == 1) coefficients[base] = coefficients[base] or (1 shl scan.approximationLow)
        }
        return Unit
    }

    // AC scans. We only need the handful of low-frequency coefficients the reduced transforms read;
    // anything beyond them cannot change the output, so the scan is skipped rather than walked.
    if (scan.spectralStart >= AC_BANDS_WE_NEED) return Unit
    return decodeProgressiveAc(reader, scan, component, base)
}

@Suppress("ReturnCount")
private fun decodeProgressiveAc(
    reader: JpegBitReader,
    scan: JpegScan,
    component: JpegComponent,
    base: Int,
): Unit? {
    val coefficients = component.coefficients
    val acTable = component.acTable ?: return null

    // Refinement of already-present AC bits is not modelled: at 1/8 and 1/4 the extra precision is
    // below the rounding of the downscale itself, so the first pass is what the picture is made of.
    if (scan.approximationHigh != 0) return Unit

    if (reader.endOfBandRun > 0) {
        reader.endOfBandRun--
        return Unit
    }

    var index = scan.spectralStart
    while (index <= scan.spectralEnd) {
        val symbol = reader.decodeHuffman(acTable) ?: return null
        val run = (symbol and 0xF0) shr 4
        val size = symbol and 0x0F
        if (size == 0) {
            if (run < ZERO_RUN_16 - 1) {
                reader.endOfBandRun = (1 shl run) - 1
                if (run > 0) reader.endOfBandRun += reader.readBits(run)
                break
            }
            index += ZERO_RUN_16
            continue
        }
        index += run
        if (index > scan.spectralEnd || index >= BLOCK_COEFFICIENTS) break
        val value = reader.receiveExtend(size)
        if (ZIGZAG[index] < RETAINED_COEFFICIENTS) {
            coefficients[base + ZIGZAG[index]] = value shl scan.approximationLow
        }
        index++
    }
    return Unit
}

/** Zig-zag order → natural block position. */
private val ZIGZAG =
    intArrayOf(
        0,
        1,
        8,
        16,
        9,
        2,
        3,
        10,
        17,
        24,
        32,
        25,
        18,
        11,
        4,
        5,
        12,
        19,
        26,
        33,
        40,
        48,
        41,
        34,
        27,
        20,
        13,
        6,
        7,
        14,
        21,
        28,
        35,
        42,
        49,
        56,
        57,
        50,
        43,
        36,
        29,
        22,
        15,
        23,
        30,
        37,
        44,
        51,
        58,
        59,
        52,
        45,
        38,
        31,
        39,
        46,
        53,
        60,
        61,
        54,
        47,
        55,
        62,
        63,
    )

private val SUPPORTED_REDUCTIONS = listOf(4, 8)

/**
 * Only the top-left 2x2 of each block is ever read, so anything at natural index >= 10 (row 2 or
 * column 2 and beyond) is discarded during entropy decode rather than stored.
 */
private const val RETAINED_COEFFICIENTS = 10
private const val AC_BANDS_WE_NEED = 3
private const val ZERO_RUN_16 = 16
