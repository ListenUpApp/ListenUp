package com.calypsan.listenup.server.imaging

import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/**
 * Encodes a [PixelBuffer] as a **baseline** JPEG at [quality] (1–100).
 *
 * Baseline, not progressive, and deliberately so. A derivative is fetched once and painted
 * immediately, so progressive's staged refinement buys nothing and costs several scans' worth of
 * encoder complexity. It is also the format that fails *visibly*: get a table or a run length wrong
 * and the picture is obviously broken, where a wrong entropy-coded WebP would be plausible garbage.
 *
 * 4:2:0 chroma subsampling, the standard Annex K Huffman tables, and quantisation scaled from the
 * standard tables. Nothing here is tuned — a cover derivative is not the place to invent a codec.
 */
internal fun encodeJpeg(
    image: PixelBuffer,
    quality: Int,
): ByteArray {
    val quantLuma = scaleQuantTable(BASE_QUANT_LUMA, quality)
    val quantChroma = scaleQuantTable(BASE_QUANT_CHROMA, quality)
    val planes = YCbCrPlanes.from(image)

    val out = Buffer()
    out.writeMarker(MARKER_SOI_BYTE)
    out.writeJfifHeader()
    out.writeQuantTables(quantLuma, quantChroma)
    out.writeFrameHeader(image.width, image.height)
    out.writeHuffmanTables()
    out.writeScanHeader()
    writeScan(out, planes, quantLuma, quantChroma)
    out.writeMarker(MARKER_EOI_BYTE)
    return out.readByteArray()
}

/**
 * The image as one full-resolution luma plane and two half-resolution chroma planes.
 *
 * Chroma is averaged over each 2x2 group rather than sampled, for the same reason [resizedTo]
 * averages: dropping three of every four samples aliases the edges of type, and a cover is mostly
 * type. The eye's low chroma acuity is what makes 4:2:0 free; sampling artefacts are not.
 */
private class YCbCrPlanes(
    val luma: IntArray,
    val blueDiff: IntArray,
    val redDiff: IntArray,
    val width: Int,
    val height: Int,
    val chromaWidth: Int,
    val chromaHeight: Int,
) {
    companion object {
        fun from(image: PixelBuffer): YCbCrPlanes {
            val luma = IntArray(image.width * image.height)
            for (index in image.pixels.indices) {
                val pixel = image.pixels[index]
                luma[index] = (Y_RED * red(pixel) + Y_GREEN * green(pixel) + Y_BLUE * blue(pixel)) / FIXED_POINT
            }

            val chromaWidth = ceilDiv(image.width, 2)
            val chromaHeight = ceilDiv(image.height, 2)
            val blueDiff = IntArray(chromaWidth * chromaHeight)
            val redDiff = IntArray(chromaWidth * chromaHeight)
            for (y in 0 until chromaHeight) {
                for (x in 0 until chromaWidth) {
                    var redSum = 0
                    var greenSum = 0
                    var blueSum = 0
                    var count = 0
                    for (dy in 0 until 2) {
                        for (dx in 0 until 2) {
                            val sourceY = (y * 2 + dy).coerceAtMost(image.height - 1)
                            val sourceX = (x * 2 + dx).coerceAtMost(image.width - 1)
                            val pixel = image.pixels[sourceY * image.width + sourceX]
                            redSum += red(pixel)
                            greenSum += green(pixel)
                            blueSum += blue(pixel)
                            count++
                        }
                    }
                    val r = redSum / count
                    val g = greenSum / count
                    val b = blueSum / count
                    val at = y * chromaWidth + x
                    blueDiff[at] = (CB_RED * r + CB_GREEN * g + CB_BLUE * b) / FIXED_POINT + LEVEL_SHIFT
                    redDiff[at] = (CR_RED * r + CR_GREEN * g + CR_BLUE * b) / FIXED_POINT + LEVEL_SHIFT
                }
            }
            return YCbCrPlanes(
                luma,
                blueDiff,
                redDiff,
                image.width,
                image.height,
                chromaWidth,
                chromaHeight,
            )
        }
    }
}

/**
 * Walks the MCU grid, encoding four luma blocks then one of each chroma per MCU.
 *
 * The grid is sized in whole MCUs, so an image that is not a multiple of 16 gets padded blocks whose
 * samples are clamped from the edge. The frame header still carries the true dimensions, so a
 * decoder crops the padding away — but the padding must be *edge replication* rather than black,
 * or every cover would gain a dark fringe on two sides.
 */
private fun writeScan(
    out: Buffer,
    planes: YCbCrPlanes,
    quantLuma: IntArray,
    quantChroma: IntArray,
) {
    val writer = JpegBitWriter(out)
    val dcLuma = HuffmanEncoder(STANDARD_DC_LUMA_COUNTS, STANDARD_DC_LUMA_VALUES)
    val acLuma = HuffmanEncoder(STANDARD_AC_LUMA_COUNTS, STANDARD_AC_LUMA_VALUES)
    val dcChroma = HuffmanEncoder(STANDARD_DC_CHROMA_COUNTS, STANDARD_DC_CHROMA_VALUES)
    val acChroma = HuffmanEncoder(STANDARD_AC_CHROMA_COUNTS, STANDARD_AC_CHROMA_VALUES)

    val mcusPerLine = ceilDiv(planes.width, MCU_PIXELS)
    val mcusPerColumn = ceilDiv(planes.height, MCU_PIXELS)
    var lumaPredictor = 0
    var bluePredictor = 0
    var redPredictor = 0

    for (mcuRow in 0 until mcusPerColumn) {
        for (mcuColumn in 0 until mcusPerLine) {
            for (v in 0 until 2) {
                for (h in 0 until 2) {
                    val block =
                        gather(
                            planes.luma,
                            planes.width,
                            planes.height,
                            (mcuColumn * 2 + h) * DCT_SIZE,
                            (mcuRow * 2 + v) * DCT_SIZE,
                        )
                    lumaPredictor = writeBlock(writer, block, quantLuma, dcLuma, acLuma, lumaPredictor)
                }
            }
            val blue =
                gather(
                    planes.blueDiff,
                    planes.chromaWidth,
                    planes.chromaHeight,
                    mcuColumn * DCT_SIZE,
                    mcuRow * DCT_SIZE,
                )
            bluePredictor = writeBlock(writer, blue, quantChroma, dcChroma, acChroma, bluePredictor)
            val red =
                gather(
                    planes.redDiff,
                    planes.chromaWidth,
                    planes.chromaHeight,
                    mcuColumn * DCT_SIZE,
                    mcuRow * DCT_SIZE,
                )
            redPredictor = writeBlock(writer, red, quantChroma, dcChroma, acChroma, redPredictor)
        }
    }
    writer.flush()
}

/** One 8x8 block of samples, level-shifted, with out-of-bounds reads clamped to the edge. */
private fun gather(
    plane: IntArray,
    width: Int,
    height: Int,
    originX: Int,
    originY: Int,
): DoubleArray {
    val block = DoubleArray(BLOCK_COEFFICIENTS)
    for (y in 0 until DCT_SIZE) {
        val sourceY = (originY + y).coerceAtMost(height - 1)
        for (x in 0 until DCT_SIZE) {
            val sourceX = (originX + x).coerceAtMost(width - 1)
            block[y * DCT_SIZE + x] = (plane[sourceY * width + sourceX] - LEVEL_SHIFT).toDouble()
        }
    }
    return block
}

/** Transforms, quantises and entropy-codes one block. Returns the DC value for the next predictor. */
private fun writeBlock(
    writer: JpegBitWriter,
    block: DoubleArray,
    quant: IntArray,
    dcTable: HuffmanEncoder,
    acTable: HuffmanEncoder,
    predictor: Int,
): Int {
    val coefficients = quantise(forwardDct(block), quant)

    val diff = coefficients[0] - predictor
    val dcCategory = magnitudeCategory(diff)
    dcTable.write(writer, dcCategory)
    if (dcCategory > 0) writer.writeBits(magnitudeBits(diff, dcCategory), dcCategory)

    var zeroRun = 0
    for (k in 1 until BLOCK_COEFFICIENTS) {
        val value = coefficients[ZIGZAG[k]]
        if (value == 0) {
            zeroRun++
            continue
        }
        // Runs longer than 15 are chopped into ZRLs, each standing for sixteen zeroes.
        while (zeroRun > MAX_ZERO_RUN) {
            acTable.write(writer, ZERO_RUN_LENGTH_SYMBOL)
            zeroRun -= ZERO_RUN_LENGTH
        }
        val category = magnitudeCategory(value)
        acTable.write(writer, zeroRun shl NIBBLE_BITS or category)
        writer.writeBits(magnitudeBits(value, category), category)
        zeroRun = 0
    }
    // Trailing zeroes are not coded at all — end-of-block says "the rest of this block is empty".
    if (zeroRun > 0) acTable.write(writer, END_OF_BLOCK_SYMBOL)

    return coefficients[0]
}

/** How many bits `abs(value)` needs — JPEG's "category", which prefixes the value itself. */
private fun magnitudeCategory(value: Int): Int {
    var remaining = if (value < 0) -value else value
    var bits = 0
    while (remaining > 0) {
        bits++
        remaining = remaining shr 1
    }
    return bits
}

/**
 * The bits JPEG writes for a value of a given category.
 *
 * Negative values are stored as `value - 1` truncated to [category] bits, which is what makes the
 * decoder's "below half the band means negative" test work without a sign bit.
 */
private fun magnitudeBits(
    value: Int,
    category: Int,
): Int = if (value >= 0) value else value + (1 shl category) - 1

private const val MCU_PIXELS = 16
private const val LEVEL_SHIFT = 128
private const val FIXED_POINT = 1024
private const val Y_RED = 306
private const val Y_GREEN = 601
private const val Y_BLUE = 117
private const val CB_RED = -173
private const val CB_GREEN = -339
private const val CB_BLUE = 512
private const val CR_RED = 512
private const val CR_GREEN = -429
private const val CR_BLUE = -83
private const val MAX_ZERO_RUN = 15
private const val ZERO_RUN_LENGTH = 16
private const val ZERO_RUN_LENGTH_SYMBOL = 0xF0
private const val END_OF_BLOCK_SYMBOL = 0x00
private const val MARKER_SOI_BYTE = 0xD8
private const val MARKER_EOI_BYTE = 0xD9
