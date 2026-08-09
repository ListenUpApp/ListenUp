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
import kotlinx.coroutines.sync.Semaphore
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

    /**
     * Bounds concurrent [openFile] fetches. See [fetchCoverBlocking]'s KDoc for why the bound
     * exists — this is not an arbitrary throttle, it is what stands between a slow server and
     * a frozen transport bar in the car.
     */
    private val fetchSemaphore = Semaphore(MAX_CONCURRENT_FETCHES)

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = MIME_TYPE

    /**
     * Blocks on the fetch when the cover is not cached. `openFile` is a synchronous binder
     * callback with no suspend context and is documented as permitted to block; the same
     * `runBlocking`-at-a-synchronous-boundary pattern is used by `AudioTokenAuthenticator`.
     * [FETCH_TIMEOUT_MS] bounds how long any one fetch can hold its thread, and
     * [fetchSemaphore] bounds how many can be in flight at once — see [fetchCoverBlocking]'s
     * KDoc.
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
            fetchCoverBlocking(bookId)
        }
        if (!file.exists()) {
            logger.debug { "No cover available for $bookId" }
            throw FileNotFoundException("No cover for $bookId")
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /**
     * Fetches [bookId]'s cover from the server, bounded to [MAX_CONCURRENT_FETCHES] concurrent
     * in-flight fetches.
     *
     * `openFile` runs on one of the process's binder threads, and that pool is small (~16,
     * fixed by the platform) and **shared with MediaSession transport commands** — play,
     * pause, seek all arrive over the same pool. A cold-cache grid browse in Android Auto can
     * trigger a dozen `openFile` calls at once; letting every one of them block on the network
     * for up to [FETCH_TIMEOUT_MS] could exhaust the pool and freeze transport controls in the
     * car for the duration of the timeout — worse than the blank covers this provider exists to
     * fix.
     *
     * So concurrency is bounded, and a request that cannot get a slot **fails fast rather than
     * queues**: queueing on the semaphore would still hold the calling binder thread hostage,
     * which defeats the point of the bound. A skipped fetch just means [openFile] falls through
     * to its "no cover" path, Auto draws its own placeholder, and the cover shows up on a later
     * browse once one of the in-flight fetches has populated the cache.
     */
    private fun fetchCoverBlocking(bookId: String) {
        if (!fetchSemaphore.tryAcquire()) {
            logger.debug { "Skipping cover fetch for $bookId: $MAX_CONCURRENT_FETCHES already in flight" }
            return
        }
        try {
            runBlocking {
                withTimeoutOrNull(FETCH_TIMEOUT_MS) { fetcher.download(BookId(bookId)) }
            }
        } finally {
            fetchSemaphore.release()
        }
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

    // `internal`, not `private`: CoverContentProviderTest asserts the concurrency bound
    // directly against MAX_CONCURRENT_FETCHES rather than duplicating the literal.
    internal companion object {
        const val MIME_TYPE = "image/jpeg"
        const val FETCH_TIMEOUT_MS = 5_000L
        const val MAX_CONCURRENT_FETCHES = 4
    }
}
