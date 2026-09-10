package com.calypsan.listenup.server.compression.zip

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A zip holding one DEFLATE entry [name] of [size] zero bytes, written by `java.util.zip` in chunks
 * so the fixture never holds the payload in memory. Zeros compress roughly a thousandfold, which is
 * the point: a kilobytes-sized archive whose directory honestly declares an entry far larger than
 * any consumer's budget.
 */
internal fun zipWithZeroFilledEntry(
    name: String,
    size: Long,
): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        zip.putNextEntry(ZipEntry(name))
        val chunk = ByteArray(CHUNK_BYTES)
        var remaining = size
        while (remaining > 0) {
            val n = minOf(remaining, chunk.size.toLong()).toInt()
            zip.write(chunk, 0, n)
            remaining -= n
        }
        zip.closeEntry()
    }
    return out.toByteArray()
}

/**
 * A copy of this zip whose central-directory record for [entryName] declares [declared] as the
 * uncompressed size, with the entry's content left exactly as it was.
 *
 * This is the shape a consumer must survive: the directory is a claim the archive makes about
 * itself, and a reader that sizes anything from it has trusted its input. The record is found by
 * following the end-of-central-directory offset — the route [ZipReader] itself takes — so a stray
 * signature inside entry data can never be mistaken for it.
 */
internal fun ByteArray.withDeclaredUncompressedSize(
    entryName: String,
    declared: Long,
): ByteArray {
    require(declared in 0 until ZIP64_SENTINEL) { "declared size must fit a classic 32-bit field" }
    val patched = copyOf()
    val eocd = patched.lastIndexOf(EOCD_SIGNATURE)
    check(eocd >= 0) { "not a zip: no end-of-central-directory record" }
    val entryCount = patched.readU16LE(eocd + EOCD_TOTAL_ENTRIES)
    var at = patched.readU32LE(eocd + EOCD_CD_OFFSET).toInt()
    repeat(entryCount) {
        check(patched.readU32LE(at) == CDH_SIGNATURE) { "central-directory record expected at $at" }
        val nameLen = patched.readU16LE(at + CDH_NAME_LEN)
        val extraLen = patched.readU16LE(at + CDH_EXTRA_LEN)
        val commentLen = patched.readU16LE(at + CDH_COMMENT_LEN)
        val name = patched.decodeToString(at + CDH_FIXED_SIZE, at + CDH_FIXED_SIZE + nameLen)
        if (name == entryName) {
            patched.writeU32LE(at + CDH_UNCOMPRESSED_SIZE, declared)
            return patched
        }
        at += CDH_FIXED_SIZE + nameLen + extraLen + commentLen
    }
    error("no central-directory record for '$entryName'")
}

private fun ByteArray.lastIndexOf(pattern: ByteArray): Int {
    for (start in size - pattern.size downTo 0) {
        if (pattern.indices.all { this[start + it] == pattern[it] }) return start
    }
    return -1
}

private fun ByteArray.readU16LE(at: Int): Int = (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.readU32LE(at: Int): Long = readU16LE(at).toLong() or (readU16LE(at + 2).toLong() shl 16)

private fun ByteArray.writeU32LE(
    at: Int,
    value: Long,
) {
    repeat(4) { this[at + it] = ((value shr 8 * it) and 0xFF).toByte() }
}

private const val CHUNK_BYTES = 64 * 1024
private const val ZIP64_SENTINEL = 0xFFFF_FFFFL

private val EOCD_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
private const val EOCD_TOTAL_ENTRIES = 10
private const val EOCD_CD_OFFSET = 16

private const val CDH_SIGNATURE = 0x0201_4B50L
private const val CDH_UNCOMPRESSED_SIZE = 24
private const val CDH_NAME_LEN = 28
private const val CDH_EXTRA_LEN = 30
private const val CDH_COMMENT_LEN = 32
private const val CDH_FIXED_SIZE = 46
