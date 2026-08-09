package com.calypsan.listenup.client.automotive

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.FileNotFoundException

private val logger = KotlinLogging.logger {}

/** Resolves a book ID to its cover file on disk. A seam so the provider is testable. */
fun interface CoverFileLocator {
    fun coverFile(bookId: String): File
}

/** Fetches and persists a book's cover from the server. A seam so the provider is testable. */
fun interface CoverFetcher {
    suspend fun download(bookId: BookId): AppResult<Boolean>
}

/**
 * Serves book cover art over `content://` so Android Auto can render it.
 *
 * Auto accepts only `content://` and `android.resource://` artwork URIs — `file://` is
 * rejected, including paths inside app-private storage, which is why this exists.
 *
 * The provider is **not exported**. It is reachable only through the URI-permission grant
 * [com.calypsan.listenup.client.playback.ListenUpSessionCallback] issues to trusted media
 * controllers on connect, so no other app on the device can enumerate the user's library art.
 *
 * On a cache miss it fetches and persists the cover, then serves it — local first, network as
 * fallback, result cached for offline. Offline with no cached cover throws
 * [FileNotFoundException] and Auto draws its own placeholder.
 */
class CoverContentProvider :
    ContentProvider(),
    KoinComponent {
    // Resolved LAZILY, on first property access inside openFile. Android instantiates
    // ContentProviders between Application.attachBaseContext and Application.onCreate, so
    // Koin has not started when onCreate runs — touching these there crashes cold start.
    private val locator: CoverFileLocator by inject()
    private val fetcher: CoverFetcher by inject()

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = MIME_TYPE

    /**
     * Blocks on the fetch when the cover is not cached. `openFile` is a synchronous binder
     * callback with no suspend context and is documented as permitted to block; the same
     * `runBlocking`-at-a-synchronous-boundary pattern is used by `AudioTokenAuthenticator`.
     * [FETCH_TIMEOUT_MS] bounds it so an unreachable server cannot pin binder threads.
     */
    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        if (mode != "r") {
            throw FileNotFoundException("Cover art is read-only (mode=$mode)")
        }
        val bookId = CoverUri.bookIdFrom(uri) ?: throw FileNotFoundException("Not a cover URI")
        val file = locator.coverFile(bookId)

        if (!file.exists()) {
            runBlocking {
                withTimeoutOrNull(FETCH_TIMEOUT_MS) { fetcher.download(BookId(bookId)) }
            }
        }
        if (!file.exists()) {
            logger.debug { "No cover available for $bookId" }
            throw FileNotFoundException("No cover for $bookId")
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val bookId = CoverUri.bookIdFrom(uri) ?: return null
        val file = locator.coverFile(bookId)
        if (!file.exists()) return null

        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(
                Array<Any?>(columns.size) { index ->
                    when (columns[index]) {
                        OpenableColumns.DISPLAY_NAME -> file.name
                        OpenableColumns.SIZE -> file.length()
                        else -> null
                    }
                },
            )
        }
    }

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        const val MIME_TYPE = "image/jpeg"
        const val FETCH_TIMEOUT_MS = 10_000L
    }
}
