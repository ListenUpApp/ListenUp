package com.calypsan.listenup.client.diagnostics

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.BlobFileSource
import com.calypsan.listenup.client.data.remote.ArchiveUploadApiContract
import com.calypsan.listenup.client.data.settings.seedServerUrlFromOrigin
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.ImportRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.core.ServerUrl
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.set
import org.w3c.dom.Worker
import org.w3c.files.File
import org.koin.core.Koin
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What an end-to-end browser archive upload observed. Plain values, for the same reason
 * [BlobUploadProbe] uses them: the Koin graph and the upload API stay `internal` to this module.
 */
data class ArchiveUploadProbe(
    /** The class the browser graph bound as its archive upload API — the production wiring, by name. */
    val boundApi: String?,
    /** The import id the server minted for the upload, or null if it never answered one. */
    val importId: String?,
    /** Whether the import the upload started was deleted again, leaving the server as it was. */
    val cleanedUp: Boolean,
    /** Message from whatever failed, or null when the upload completed. */
    val failure: String?,
)

/**
 * Uploads an Audiobookshelf-shaped zip of a little over [paddingBytes] bytes to a real server
 * through the browser graph's own archive upload API, then deletes the import it started.
 *
 * The zip is built so only a complete upload can succeed: a [paddingBytes] entry comes first and
 * the `absdatabase.sqlite` entry the server extracts comes after it, and a zip is read from its end
 * — the central directory there points past the padding to that entry. A truncated or short body
 * leaves the server nothing it can extract, and the upload is refused.
 *
 * It never sets the server up itself. `AuthArcTest` owns that once-per-boot transition and fails if
 * any other spec gets there first, so this waits for the server to have its first admin.
 *
 * Never throws: whatever fails is reported, so a failure reads as a failed assertion.
 */
@Suppress("TooGenericExceptionCaught")
suspend fun probeArchiveUpload(
    worker: Worker,
    dbName: String,
    email: String,
    password: String,
    paddingBytes: Int,
): ArchiveUploadProbe {
    val app = browserGraph(worker, dbName)

    fun failed(
        api: ArchiveUploadApiContract?,
        why: String,
    ) = ArchiveUploadProbe(api?.let { it::class.simpleName }, importId = null, cleanedUp = false, failure = why)

    return try {
        if (!app.koin.awaitServerSetUp()) return failed(null, "the server was never set up")
        if (!app.koin.signInProbeAdmin(email, password)) return failed(null, "never reached Authenticated")

        val api = app.koin.get<ArchiveUploadApiContract>()
        val file = storedZip(paddingBytes = paddingBytes, filename = "probe.audiobookshelf")
        when (val sent = api.uploadAbsBackup(BlobFileSource(file))) {
            is AppResult.Failure -> {
                failed(api, "uploadAbsBackup failed: ${sent.error}")
            }

            is AppResult.Success -> {
                val deleted = app.koin.get<ImportRepository>().deleteImport(sent.data.id)
                ArchiveUploadProbe(
                    boundApi = api::class.simpleName,
                    importId = sent.data.id.value,
                    cleanedUp = deleted is AppResult.Success,
                    failure = null,
                )
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        failed(null, "probe threw: $e")
    } finally {
        app.close()
    }
}

private val SETUP_WAIT = 90.seconds
private val SETUP_POLL = 500.milliseconds

/**
 * Waits, within [SETUP_WAIT], until the server no longer needs setup — answering whether it got
 * there. Seeds the server URL first, as [signInProbeAdmin] does: an isolated graph inherits none.
 */
private suspend fun Koin.awaitServerSetUp(): Boolean {
    val serverConfig = get<ServerConfig>()
    if (!serverConfig.hasServerConfigured()) {
        serverConfig.setServerUrl(
            ServerUrl(seedServerUrlFromOrigin(stored = null, origin = window.location.origin)),
        )
    }
    val authSession = get<AuthSession>()
    return withTimeoutOrNull(SETUP_WAIT) {
        while (true) {
            authSession.initializeAuthState()
            if (authSession.authState.value !is AuthState.NeedsSetup) break
            delay(SETUP_POLL)
        }
    } != null
}

/** The bytes of the small database entry; the upload only extracts it, so any content will do. */
private val DATABASE_BYTES = "not really sqlite".encodeToByteArray()

private const val DATABASE_ENTRY = "absdatabase.sqlite"

private const val LOCAL_HEADER_SIGNATURE = 0x04034b50
private const val DIRECTORY_HEADER_SIGNATURE = 0x02014b50
private const val END_OF_DIRECTORY_SIGNATURE = 0x06054b50
private const val BITS_PER_BYTE = 8
private const val BYTE_VALUES = 256
private const val BYTE_MASK = 0xFF

/** The reflected CRC-32 polynomial zip uses. */
private const val CRC_POLYNOMIAL = 0xEDB88320.toInt()
private const val PADDING_ENTRY = "padding.bin"

/**
 * A two-entry STORED zip: [paddingBytes] zero bytes as `padding.bin`, then `absdatabase.sqlite`.
 * The padding goes into the [File] as an untouched [Uint8Array], never copied through a `ByteArray`.
 */
private fun storedZip(
    paddingBytes: Int,
    filename: String,
): File {
    val padding = StoredEntry(PADDING_ENTRY, size = paddingBytes, crc = crc32OfZeros(paddingBytes), offset = 0)
    val paddingHeader = padding.localHeader()
    val database =
        StoredEntry(
            DATABASE_ENTRY,
            size = DATABASE_BYTES.size,
            crc = crc32(DATABASE_BYTES),
            offset = paddingHeader.size + paddingBytes,
        )
    val databaseHeader = database.localHeader()
    val directoryOffset = database.offset + databaseHeader.size + DATABASE_BYTES.size
    val directory = padding.directoryHeader() + database.directoryHeader()
    val end =
        LittleEndian()
            .int(END_OF_DIRECTORY_SIGNATURE)
            .short(0)
            .short(0)
            .short(2)
            .short(2)
            .int(directory.size)
            .int(directoryOffset)
            .short(0)
            .bytes()
    val parts: Array<Any> =
        arrayOf(
            paddingHeader.toUint8Array(),
            Uint8Array(paddingBytes),
            databaseHeader.toUint8Array(),
            DATABASE_BYTES.toUint8Array(),
            directory.toUint8Array(),
            end.toUint8Array(),
        )
    return File(parts, filename)
}

private class StoredEntry(
    val name: String,
    val size: Int,
    val crc: Int,
    val offset: Int,
) {
    private val nameBytes = name.encodeToByteArray()

    fun localHeader(): ByteArray =
        LittleEndian()
            .int(LOCAL_HEADER_SIGNATURE)
            .short(VERSION)
            .short(0)
            .short(STORED)
            .short(0)
            .short(DOS_DATE)
            .int(crc)
            .int(size)
            .int(size)
            .short(nameBytes.size)
            .short(0)
            .bytes() + nameBytes

    fun directoryHeader(): ByteArray =
        LittleEndian()
            .int(DIRECTORY_HEADER_SIGNATURE)
            .short(VERSION)
            .short(VERSION)
            .short(0)
            .short(STORED)
            .short(0)
            .short(DOS_DATE)
            .int(crc)
            .int(size)
            .int(size)
            .short(nameBytes.size)
            .short(0)
            .short(0)
            .short(0)
            .short(0)
            .int(0)
            .int(offset)
            .bytes() + nameBytes

    private companion object {
        const val VERSION = 20
        const val STORED = 0

        /** 1980-01-01, the earliest date a zip can hold. */
        const val DOS_DATE = 0x21
    }
}

private class LittleEndian {
    private val out = mutableListOf<Byte>()

    fun short(value: Int) = apply { repeat(2) { out += (value ushr it * BITS_PER_BYTE).toByte() } }

    fun int(value: Int) = apply { repeat(4) { out += (value ushr it * BITS_PER_BYTE).toByte() } }

    fun bytes(): ByteArray = out.toByteArray()
}

private val CRC_TABLE =
    IntArray(BYTE_VALUES) { n ->
        var c = n
        repeat(BITS_PER_BYTE) { c = if (c and 1 != 0) (c ushr 1) xor CRC_POLYNOMIAL else c ushr 1 }
        c
    }

private fun crc32(bytes: ByteArray): Int {
    var crc = -1
    for (b in bytes) crc = CRC_TABLE[(crc xor b.toInt()) and BYTE_MASK] xor (crc ushr BITS_PER_BYTE)
    return crc.inv()
}

private fun crc32OfZeros(count: Int): Int {
    var crc = -1
    repeat(count) { crc = CRC_TABLE[crc and BYTE_MASK] xor (crc ushr BITS_PER_BYTE) }
    return crc.inv()
}

private fun ByteArray.toUint8Array(): Uint8Array {
    val array = Uint8Array(size)
    forEachIndexed { index, byte -> array[index] = byte }
    return array
}
