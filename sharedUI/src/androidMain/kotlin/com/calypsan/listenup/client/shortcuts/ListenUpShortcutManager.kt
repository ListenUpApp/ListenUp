package com.calypsan.listenup.client.shortcuts

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.calypsan.listenup.client.MainActivity
import com.calypsan.listenup.client.composeapp.R
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.domain.repository.HomeRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val logger = KotlinLogging.logger {}

private const val MAX_ICON_SIZE = 128

/**
 * Manages dynamic app shortcuts for ListenUp.
 *
 * Dynamic shortcuts show recently played books in the launcher long-press menu,
 * allowing users to jump directly to a specific audiobook.
 *
 * Features:
 * - Shows up to 4 recent books as shortcuts
 * - Uses book cover art as shortcut icon (with fallback)
 * - Updates automatically when playback state changes
 * - Respects Android's shortcut limits
 *
 * Thread safety:
 * - All shortcut operations run on IO dispatcher
 * - Can be called from any thread
 *
 * Usage:
 * ```kotlin
 * // Call after playback starts or completes
 * shortcutManager.updateShortcuts()
 * ```
 */
class ListenUpShortcutManager(
    private val context: Context,
    private val homeRepository: HomeRepository,
    private val scope: CoroutineScope,
) {
    /**
     * Updates dynamic shortcuts with recently played books.
     *
     * Called when:
     * - Playback starts (new book becomes recent)
     * - Playback completes (book moves up in recents)
     * - App launches (refresh potentially stale shortcuts)
     *
     * This method is safe to call frequently - it handles its own threading
     * and will not block the caller.
     */
    fun updateShortcuts() {
        scope.launch {
            updateShortcutsInternal()
        }
    }

    /**
     * Internal implementation that runs on background thread.
     *
     * Widened from private to internal solely so the cancellation contract can be
     * exercised directly in androidHostTest (the public [updateShortcuts] launches
     * fire-and-forget, so its propagation is otherwise unobservable).
     */
    internal suspend fun updateShortcutsInternal() =
        withContext(Dispatchers.IO) {
            try {
                // Get recent books from local database
                val result = homeRepository.getContinueListening(ShortcutActions.MAX_BOOK_SHORTCUTS)

                when (result) {
                    is AppResult.Success -> {
                        val books = result.data
                        if (books.isEmpty()) {
                            logger.debug { "No recent books, clearing dynamic shortcuts" }
                            clearDynamicShortcuts()
                            return@withContext
                        }

                        logger.debug { "Updating shortcuts with ${books.size} recent books" }
                        val shortcuts = books.mapNotNull { book -> createBookShortcut(book) }
                        setDynamicShortcuts(shortcuts)
                    }

                    is AppResult.Failure -> {
                        logger.warn { "Failed to get recent books for shortcuts: ${result.message}" }
                        // Don't clear existing shortcuts on failure - keep stale data
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Error updating shortcuts" }
            }
        }

    /**
     * Creates a shortcut for a specific book.
     *
     * @param book The book to create a shortcut for
     * @return ShortcutInfoCompat or null if creation fails
     */
    private suspend fun createBookShortcut(book: ContinueListeningBook): ShortcutInfoCompat? =
        try {
            val shortcutId = "${ShortcutActions.SHORTCUT_ID_BOOK_PREFIX}${book.bookId}"

            // Create intent with book ID
            val intent =
                Intent(context, MainActivity::class.java).apply {
                    action = ShortcutActions.PLAY_BOOK
                    putExtra(ShortcutActions.EXTRA_BOOK_ID, book.bookId)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }

            // Load cover art icon or use default
            val icon = loadCoverIcon(book.coverPath) ?: createDefaultIcon()

            ShortcutInfoCompat
                .Builder(context, shortcutId)
                .setShortLabel(book.title.take(MAX_SHORT_LABEL_LENGTH))
                .setLongLabel("${book.title} - ${book.authorNames}".take(MAX_LONG_LABEL_LENGTH))
                .setIcon(icon)
                .setIntent(intent)
                .setRank(0) // Most recent first
                .build()
        } catch (e: Exception) {
            logger.error(e) { "Failed to create shortcut for book: ${book.bookId}" }
            null
        }

    /**
     * Loads book cover art as an icon.
     *
     * @param coverPath Local file path to cover image
     * @return IconCompat or null if loading fails
     */
    private fun loadCoverIcon(coverPath: String?): IconCompat? {
        if (coverPath == null) return null

        return try {
            val file = File(coverPath)
            if (!file.exists()) {
                logger.debug { "Cover file not found: $coverPath" }
                return null
            }

            // Decode with reduced size for icon usage
            val options =
                BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
            BitmapFactory.decodeFile(coverPath, options)

            // Calculate sample size for target icon size
            options.inSampleSize = calculateSampleSize(options, MAX_ICON_SIZE, MAX_ICON_SIZE)
            options.inJustDecodeBounds = false

            val bitmap = BitmapFactory.decodeFile(coverPath, options)
            if (bitmap == null) {
                logger.warn { "Failed to decode cover: $coverPath" }
                return null
            }

            // Scale to exact size and make square
            val scaledBitmap = createSquareBitmap(bitmap, MAX_ICON_SIZE)
            IconCompat.createWithAdaptiveBitmap(scaledBitmap)
        } catch (e: Exception) {
            logger.error(e) { "Error loading cover icon: $coverPath" }
            null
        }
    }

    /**
     * Creates default audiobook icon for when cover art is unavailable.
     */
    private fun createDefaultIcon(): IconCompat =
        IconCompat.createWithResource(context, R.drawable.ic_shortcut_audiobook)

    /**
     * Calculates sample size for bitmap decoding.
     *
     * Visible for testing.
     */
    internal fun calculateSampleSize(
        options: BitmapFactory.Options,
        targetWidth: Int,
        targetHeight: Int,
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var sampleSize = 1

        if (height > targetHeight || width > targetWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while (halfHeight / sampleSize >= targetHeight &&
                halfWidth / sampleSize >= targetWidth
            ) {
                sampleSize *= 2
            }
        }

        return sampleSize
    }

    /**
     * Creates a square bitmap from the center of the source.
     *
     * Visible for testing.
     */
    internal fun createSquareBitmap(
        source: Bitmap,
        targetSize: Int,
    ): Bitmap {
        val size = minOf(source.width, source.height)
        val x = (source.width - size) / 2
        val y = (source.height - size) / 2

        val cropped = Bitmap.createBitmap(source, x, y, size, size)
        return if (cropped.width != targetSize) {
            Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)
        } else {
            cropped
        }
    }

    /**
     * Sets the dynamic shortcuts, replacing any existing ones.
     */
    private fun setDynamicShortcuts(shortcuts: List<ShortcutInfoCompat>) {
        try {
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
            logger.info { "Set ${shortcuts.size} dynamic shortcuts" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to set dynamic shortcuts" }
        }
    }

    /**
     * Clears all dynamic shortcuts.
     */
    private fun clearDynamicShortcuts() {
        try {
            ShortcutManagerCompat.removeAllDynamicShortcuts(context)
            logger.debug { "Cleared all dynamic shortcuts" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to clear dynamic shortcuts" }
        }
    }

    /**
     * Reports shortcut usage for ranking optimization.
     *
     * Call this when a shortcut is activated to help Android
     * prioritize frequently used shortcuts.
     *
     * @param shortcutId The ID of the activated shortcut
     */
    fun reportShortcutUsed(shortcutId: String) {
        try {
            ShortcutManagerCompat.reportShortcutUsed(context, shortcutId)
            logger.debug { "Reported shortcut usage: $shortcutId" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to report shortcut usage: $shortcutId" }
        }
    }

    companion object {
        // Android limits for shortcut labels
        private const val MAX_SHORT_LABEL_LENGTH = 25
        private const val MAX_LONG_LABEL_LENGTH = 50
    }
}
