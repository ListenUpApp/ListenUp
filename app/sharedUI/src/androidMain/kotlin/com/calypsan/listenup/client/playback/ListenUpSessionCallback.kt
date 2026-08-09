package com.calypsan.listenup.client.playback

import android.app.PendingIntent
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.client.composeapp.R
import com.calypsan.listenup.client.automotive.AutoBrowseErrors
import com.calypsan.listenup.client.automotive.BrowseTree
import com.calypsan.listenup.client.automotive.BrowseTreeProvider
import com.calypsan.listenup.client.automotive.CoverUri
import com.calypsan.listenup.client.automotive.CustomActions
import com.calypsan.listenup.client.automotive.browseNeedsSignIn
import com.calypsan.listenup.client.automotive.isLastPage
import com.calypsan.listenup.client.automotive.paginate
import com.calypsan.listenup.client.playback.cast.CastMediaItemFactory
import com.calypsan.listenup.client.playback.cast.CastPreparer
import com.calypsan.listenup.client.playback.cast.CastSessionController
import com.calypsan.listenup.client.playback.cast.CastSourceItem
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.api.result.getOrNull
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.HomeRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.voice.MediaFocus
import com.calypsan.listenup.client.voice.PlaybackIntent
import com.calypsan.listenup.client.voice.VoiceHints
import com.calypsan.listenup.client.voice.VoiceIntentResolver
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject
import com.calypsan.listenup.client.core.DurationFormatter
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * The Media3 session callback: browse tree, search, voice intents and custom commands.
 *
 * Extracted from `PlaybackService`, where it was a 736-line `inner class` reaching freely into
 * service state. Its dependencies are now named in the constructor, which is what makes it
 * testable — [PlaybackTransport] can be faked, where the enclosing service could not be.
 */
private val logger = KotlinLogging.logger {}

/** Cap on remembered voice/browse search results, keyed by query. */
private const val MAX_SEARCH_CACHE_SIZE = 5

internal class ListenUpSessionCallback(
    private val context: Context,
    private val playbackManager: PlaybackManager,
    private val browseTreeProvider: BrowseTreeProvider,
    private val voiceIntentResolver: VoiceIntentResolver,
    private val homeRepository: HomeRepository,
    private val authSession: AuthSession,
    private val positionRepository: PlaybackPositionRepository,
    private val serviceScope: CoroutineScope,
    private val transport: PlaybackTransport,
    private val uriPermissionGranter: UriPermissionGranter,
) : MediaLibrarySession.Callback {
    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val customCommands =
            AudiobookNotificationProvider.getCustomCommands() +
                listOf(CustomActions.cycleSpeedCommand(), CustomActions.seekToBookPositionCommand())

        // Limited to 3 custom actions by Android Auto guidelines.
        val customLayout =
            listOf(
                CommandButton
                    .Builder(CommandButton.ICON_SKIP_BACK_30)
                    .setDisplayName("Back 30s")
                    .setSessionCommand(
                        SessionCommand(AudiobookNotificationProvider.COMMAND_SKIP_BACK_30, Bundle.EMPTY),
                    ).build(),
                CommandButton
                    .Builder(CommandButton.ICON_UNDEFINED)
                    .setDisplayName("Speed")
                    .setCustomIconResId(R.drawable.ic_speed)
                    .setSessionCommand(CustomActions.cycleSpeedCommand())
                    .build(),
                CommandButton
                    .Builder(CommandButton.ICON_SKIP_FORWARD_30)
                    .setDisplayName("Forward 30s")
                    .setSessionCommand(
                        SessionCommand(AudiobookNotificationProvider.COMMAND_SKIP_FORWARD_30, Bundle.EMPTY),
                    ).build(),
            )

        val trust = session.classifyController(controller)
        logger.debug { "onConnect from ${controller.packageName} classified as $trust" }
        if (mayAccessCoverArt(trust)) {
            grantCoverArtAccess(controller.packageName)
        }
        return session.buildConnectionResultFor(controller, trust, customCommands, customLayout)
    }

    /**
     * Grants [packageName] read access to every book cover.
     *
     * `CoverContentProvider` is not exported, so a controller can only reach it through an
     * explicit grant. The prefix URI (via [uriPermissionGranter]) covers the whole `/covers`
     * path, so this is one grant per connection rather than one per book.
     *
     * Best-effort: a failure here costs cover art, never playback, so it must not break the
     * connection.
     *
     * `internal`, not `private`: this is the branch's highest-risk line — swapping the grantee
     * package and the URI produces no crash and no compile error, just silently blank covers in
     * a car — so [ListenUpSessionCallbackTest] calls it directly rather than only through the
     * pure [mayAccessCoverArt] policy check.
     */
    internal fun grantCoverArtAccess(packageName: String) {
        try {
            uriPermissionGranter.grantRead(packageName, CoverUri.prefixUri(context.packageName))
            logger.debug { "Granted cover art access to $packageName" }
        } catch (e: SecurityException) {
            logger.warn(e) { "Could not grant cover art access to $packageName" }
        }
    }

    // ========== Browse Operations ==========

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        if (session.classifyController(browser) == ControllerTrust.UNKNOWN) {
            logger.debug { "onGetLibraryRoot rejected for untrusted controller: ${browser.packageName}" }
            return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_PERMISSION_DENIED))
        }
        if (browseNeedsSignIn(authSession.authState.value)) {
            logger.debug { "onGetLibraryRoot: signed out — returning auth error" }
            return Futures.immediateFuture(
                LibraryResult.ofError(AutoBrowseErrors.signedOutError(context)),
            )
        }
        logger.debug { "onGetLibraryRoot" }
        val root = browseTreeProvider.getRoot()
        return Futures.immediateFuture(LibraryResult.ofItem(root, params))
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        logger.debug { "onGetChildren: parentId=$parentId, page=$page, pageSize=$pageSize" }

        if (browseNeedsSignIn(authSession.authState.value)) {
            return Futures.immediateFuture(
                LibraryResult.ofError(AutoBrowseErrors.signedOutError(context)),
            )
        }

        return CallbackToFutureAdapter.getFuture { completer ->
            serviceScope.launch {
                try {
                    val children = browseTreeProvider.getChildren(parentId)
                    val pageItems = paginate(children, page, pageSize)
                    completer.set(LibraryResult.ofItemList(ImmutableList.copyOf(pageItems), params))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(e) { "Failed to get children for $parentId" }
                    completer.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
                }
            }
            "GetChildren:$parentId"
        }
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        logger.debug { "onGetItem: mediaId=$mediaId" }

        return CallbackToFutureAdapter.getFuture { completer ->
            serviceScope.launch {
                try {
                    val item = browseTreeProvider.getItem(mediaId)
                    if (item != null) {
                        completer.set(LibraryResult.ofItem(item, null))
                    } else {
                        completer.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(e) { "Failed to get item $mediaId" }
                    completer.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
                }
            }
            "GetItem:$mediaId"
        }
    }

    // ========== Search ==========

    /**
     * LRU cache for search results.
     *
     * Bridges the gap between onSearch() and onGetSearchResult() in Media3's
     * async search pattern. Entries are removed after retrieval to prevent
     * memory leaks. Max size provides safety net for unretrieved results.
     */
    private val searchResultsCache =
        object : LinkedHashMap<String, List<MediaItem>>(
            MAX_SEARCH_CACHE_SIZE,
            0.75f,
            true, // access-order for LRU behavior
        ) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MediaItem>>?) =
                size > MAX_SEARCH_CACHE_SIZE
        }

    /**
     * Handle search queries from Google Assistant / Android Auto.
     *
     * This is the primary entry point for voice commands like:
     * "Hey Google, play The Hobbit on ListenUp"
     *
     * The Media3 search flow:
     * 1. Google sends the query to onSearch()
     * 2. We resolve via VoiceIntentResolver and cache results
     * 3. Return LibraryResult.ofVoid() to accept the query
     * 4. Call notifySearchResultChanged() to signal results are ready
     * 5. Google calls onGetSearchResult() to retrieve results
     */
    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> {
        logger.info { "onSearch: query='$query'" }

        // Perform search asynchronously
        serviceScope.launch {
            try {
                // Extract hints from params extras if available
                val extras = params?.extras
                val hints =
                    VoiceHints(
                        title = extras?.getString(MediaStore.EXTRA_MEDIA_TITLE),
                        artist = extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST),
                        album = extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM),
                        focus = extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS)?.toMediaFocus(),
                    )

                logger.debug { "Search hints: title=${hints.title}, artist=${hints.artist}, focus=${hints.focus}" }

                val intent = voiceIntentResolver.resolve(query, hints)
                logger.info { "Search resolved to: $intent" }

                val items: List<MediaItem> =
                    when (intent) {
                        is PlaybackIntent.PlayBook -> {
                            // Single match - return as playable item
                            listOfNotNull(browseTreeProvider.getBookItem(intent.bookId))
                        }

                        is PlaybackIntent.Resume -> {
                            // Resume - get the last played book
                            val lastBook =
                                homeRepository
                                    .getContinueListening(1)
                                    .getOrNull()
                                    ?.firstOrNull()
                            if (lastBook != null) {
                                listOfNotNull(browseTreeProvider.getBookItem(lastBook.bookId))
                            } else {
                                emptyList()
                            }
                        }

                        is PlaybackIntent.PlaySeriesFrom -> {
                            // Series navigation - return the target book
                            listOfNotNull(browseTreeProvider.getBookItem(intent.startBookId))
                        }

                        is PlaybackIntent.Ambiguous -> {
                            // Multiple matches - return all candidates
                            intent.candidates.mapNotNull { match ->
                                browseTreeProvider.getBookItem(match.bookId)
                            }
                        }

                        is PlaybackIntent.NotFound -> {
                            logger.warn { "No search results for: $query" }
                            emptyList()
                        }
                    }

                logger.info { "Search found ${items.size} items for query: $query" }

                // Cache results for onGetSearchResult
                searchResultsCache[query] = items

                // Notify that search results are ready
                session.notifySearchResultChanged(browser, query, items.size, params)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Search failed for query: $query" }
                // Still notify with 0 results on error
                session.notifySearchResultChanged(browser, query, 0, params)
            }
        }

        // Return immediately - results come via onGetSearchResult
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    /**
     * Return cached search results.
     *
     * Called by Android Auto after we notify that search results are ready.
     * Removes cache entry after the last page is retrieved to prevent memory leaks.
     */
    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        logger.debug { "onGetSearchResult: query='$query', page=$page, pageSize=$pageSize" }

        val items = searchResultsCache[query] ?: emptyList()
        val pageItems = paginate(items, page, pageSize)

        // Clean up cache after the last page is retrieved
        val lastPage = isLastPage(items, page, pageSize)
        if (lastPage) {
            searchResultsCache.remove(query)
            logger.debug { "Search cache cleared for query: $query" }
        }

        return Futures.immediateFuture(
            LibraryResult.ofItemList(ImmutableList.copyOf(pageItems), params),
        )
    }

    // ========== Playback Preparation ==========

    /**
     * Resolves controller-requested [MediaItem]s (voice-search queries and/or book mediaIds)
     * to the actual playable [MediaItem]s Media3 needs, applying the restored playback speed
     * as each book resolves. Shared by [onAddMediaItems] (the Auto "add to queue" callback)
     * and [onSetMediaItems] (the Auto tap-to-play / voice-search callback, which additionally
     * needs the resolved book's [PlaybackManager.PrepareResult] to compute a resume start
     * position — see [onSetMediaItems]'s KDoc) so the two callbacks never drift on how a
     * mediaId or voice query becomes a playable item.
     *
     * @return the resolved items, plus the last [PlaybackManager.PrepareResult] produced
     *   while resolving [mediaItems] (null if none of them resolved to a book). "Last" rather
     *   than "the" result because [onSetMediaItems] only relies on it when [mediaItems]
     *   contained exactly one entry — the tap-to-play/voice-search shape.
     */
    private suspend fun resolveMediaItems(
        mediaItems: List<MediaItem>,
    ): Pair<List<MediaItem>, PlaybackManager.PrepareResult?> {
        val resolvedItems = mutableListOf<MediaItem>()
        var lastPrepareResult: PlaybackManager.PrepareResult? = null

        for (item in mediaItems) {
            // Check for voice search query (Media3 pattern)
            val searchQuery = item.requestMetadata.searchQuery
            if (searchQuery != null) {
                logger.info { "Voice search detected: query='$searchQuery'" }
                val (voiceItems, voicePrepareResult) = handleVoiceSearch(item)
                resolvedItems.addAll(voiceItems)
                if (voicePrepareResult != null) lastPrepareResult = voicePrepareResult
                continue
            }

            val bookId = BrowseTree.extractBookId(item.mediaId)
            if (bookId != null) {
                // Prepare playback for this book
                val prepareResult = playbackManager.prepareForPlayback(BookId(bookId))
                if (prepareResult != null) {
                    // Build MediaItems from timeline
                    val bookItems =
                        prepareResult.timeline.files.map { file ->
                            MediaItem
                                .Builder()
                                .setMediaId(file.audioFileId)
                                .setUri(file.playbackUri)
                                .setMediaMetadata(
                                    MediaMetadata
                                        .Builder()
                                        .setTitle(prepareResult.bookTitle)
                                        .setArtist(prepareResult.bookAuthor)
                                        .setAlbumTitle(prepareResult.seriesName)
                                        .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                                        .build(),
                                ).build()
                        }
                    // Apply the restored playback speed to ExoPlayer
                    transport.applyResumeSpeed(prepareResult.resumeSpeed)
                    resolvedItems.addAll(bookItems)
                    lastPrepareResult = prepareResult
                }
            } else {
                // Not a book ID, pass through as-is
                resolvedItems.add(item)
            }
        }

        return resolvedItems to lastPrepareResult
    }

    /**
     * Called when Android Auto adds media items to the queue.
     *
     * Resolves book IDs from media items and prepares full playback timeline via
     * [resolveMediaItems]. Also handles voice search via requestMetadata.searchQuery (Media3
     * pattern).
     *
     * Voice search flow:
     * 1. User says "Hey Google, play [book name] on ListenUp"
     * 2. Android Auto/Google Assistant sends MediaItem with searchQuery in requestMetadata
     * 3. We resolve the query through VoiceIntentResolver
     * 4. Return resolved media items for playback
     */
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> {
        logger.info { "onAddMediaItems: ${mediaItems.size} items" }

        return CallbackToFutureAdapter.getFuture { completer ->
            serviceScope.launch {
                try {
                    val (resolvedItems, _) = resolveMediaItems(mediaItems)
                    completer.set(resolvedItems)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(e) { "Failed to resolve media items" }
                    completer.setException(e)
                }
            }
            "AddMediaItems"
        }
    }

    /**
     * Called when a controller sets (rather than adds to) the media items — the path Auto's
     * tap-to-play and voice search actually take. Media3's default implementation delegates
     * to [onAddMediaItems] and echoes back the controller-provided [startIndex]/[startPositionMs]
     * verbatim; Auto never supplies either (`startIndex == C.INDEX_UNSET`), so without an
     * override here every book tapped in Auto's browse list started from position zero.
     *
     * [startIndex] `!= C.INDEX_UNSET` means the controller expressed explicit start intent —
     * that's the in-app [AndroidPlaybackController.setMediaQueue] path, which always supplies
     * one — so this branch preserves [startIndex]/[startPositionMs] verbatim, keeping that path
     * byte-identical to before this override existed.
     *
     * Otherwise (Auto tap-to-play / voice search), the start index/position is decided by
     * [autoStartPosition] — see its KDoc for the full contract.
     */
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        logger.info { "onSetMediaItems: ${mediaItems.size} items, startIndex=$startIndex" }

        return CallbackToFutureAdapter.getFuture { completer ->
            serviceScope.launch {
                try {
                    val (resolvedItems, prepareResult) = resolveMediaItems(mediaItems)

                    // The queue is this book's now. currentBookId is what every progress
                    // write is keyed on, and only the VM used to set it — so a book started
                    // from Auto, the Assistant or a media button saved its position onto
                    // whatever the app happened to activate last.
                    prepareResult?.let { playbackManager.activateBook(it.timeline.bookId) }

                    val (resolvedStartIndex, resolvedStartPositionMs) =
                        autoStartPosition(
                            requestedStartIndex = startIndex,
                            requestedStartPositionMs = startPositionMs,
                            requestItemCount = mediaItems.size,
                            resumeTimeline = prepareResult?.timeline,
                            resumePositionMs = prepareResult?.resumePositionMs ?: 0L,
                        )
                    completer.set(
                        MediaSession.MediaItemsWithStartPosition(
                            resolvedItems,
                            resolvedStartIndex,
                            resolvedStartPositionMs,
                        ),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(e) { "Failed to resolve media items for onSetMediaItems" }
                    completer.setException(e)
                }
            }
            "SetMediaItems"
        }
    }

    /**
     * Handle voice search from Android Auto / Google Assistant.
     *
     * Extracts search query and extras from MediaItem's requestMetadata,
     * resolves through VoiceIntentResolver, and returns playable media items alongside the
     * resolved book's [PlaybackManager.PrepareResult] (null when nothing resolved) — see
     * [resolveMediaItems]'s KDoc for why the caller needs it too.
     */
    private suspend fun handleVoiceSearch(item: MediaItem): Pair<List<MediaItem>, PlaybackManager.PrepareResult?> {
        val searchQuery = item.requestMetadata.searchQuery ?: return emptyList<MediaItem>() to null
        val extras = item.requestMetadata.extras

        // Extract structured hints from extras (if available)
        val hints =
            VoiceHints(
                title = extras?.getString(MediaStore.EXTRA_MEDIA_TITLE),
                artist = extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST),
                album = extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM),
                focus = extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS)?.toMediaFocus(),
            )

        logger.debug { "Voice hints: title=${hints.title}, artist=${hints.artist}, focus=${hints.focus}" }

        val intent = voiceIntentResolver.resolve(searchQuery, hints)
        logger.debug { "Resolved voice intent: $intent" }

        // Convert intent to book ID
        val bookId =
            when (intent) {
                is PlaybackIntent.PlayBook -> {
                    intent.bookId
                }

                is PlaybackIntent.Resume -> {
                    homeRepository
                        .getContinueListening(1)
                        .getOrNull()
                        ?.firstOrNull()
                        ?.bookId
                }

                is PlaybackIntent.PlaySeriesFrom -> {
                    intent.startBookId
                }

                is PlaybackIntent.Ambiguous -> {
                    // Auto-play best guess if available
                    intent.bestGuess?.bookId
                }

                is PlaybackIntent.NotFound -> {
                    logger.warn { "No match found for voice query: ${intent.originalQuery}" }
                    null
                }
            }

        if (bookId == null) {
            logger.warn { "Could not resolve book ID from voice intent: $intent" }
            return emptyList<MediaItem>() to null
        }

        logger.info { "Playing book from voice search: $bookId" }

        // Prepare playback for the book
        val prepareResult = playbackManager.prepareForPlayback(BookId(bookId))
        if (prepareResult == null) {
            logger.error { "Failed to prepare book for voice playback: $bookId" }
            return emptyList<MediaItem>() to null
        }

        // Build MediaItems from timeline
        val items =
            prepareResult.timeline.files.map { file ->
                MediaItem
                    .Builder()
                    .setMediaId(file.audioFileId)
                    .setUri(file.playbackUri)
                    .setMediaMetadata(
                        MediaMetadata
                            .Builder()
                            .setTitle(prepareResult.bookTitle)
                            .setArtist(prepareResult.bookAuthor)
                            .setAlbumTitle(prepareResult.seriesName)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                            .build(),
                    ).build()
            }
        return items to prepareResult
    }

    /**
     * Handle playback resumption from system UI.
     *
     * Called when user taps "Resume ListenUp" from Android Auto, Wear OS, or system
     * notifications after device reboot.
     *
     * This is the 3-arg overload — the 2-arg `onPlaybackResumption(mediaSession, controller)`
     * is the one that's deprecated (in favor of this one), not the reverse. [isForPlayback]
     * distinguishes why the system is asking: `false` means it only wants the item/metadata to
     * populate a resumption notification (e.g. right after a reboot), with no immediate
     * intention to start audio; `true` means the user actually pressed play and playback should
     * start once resolved. Our preparation work — reading the last-played book and resolving
     * its start position — is needed to answer either request, so this implementation is
     * identical for both flag values.
     */
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        logger.info { "Playback resumption requested (isForPlayback=$isForPlayback)" }

        return CallbackToFutureAdapter.getFuture { completer ->
            serviceScope.launch {
                try {
                    // Get the last played book from the repository
                    val lastPlayed =
                        when (val r = positionRepository.getLastPlayedBook()) {
                            is AppResult.Success -> {
                                r.data
                            }

                            is AppResult.Failure -> {
                                logger.warn { "Failed to read last played book: ${r.error.message}" }
                                null
                            }
                        }

                    if (lastPlayed == null) {
                        logger.warn { "No last played book found for resumption" }
                        completer.setException(IllegalStateException("No book to resume"))
                        return@launch
                    }

                    logger.info { "Resuming book: ${lastPlayed.bookId.value} at ${lastPlayed.positionMs}ms" }

                    // Prepare playback for the book
                    val prepareResult = playbackManager.prepareForPlayback(lastPlayed.bookId)

                    if (prepareResult == null) {
                        logger.error { "Failed to prepare book for resumption" }
                        completer.setException(IllegalStateException("Failed to prepare book"))
                        return@launch
                    }

                    // Same reason as onSetMediaItems: resumption is a play path the VM
                    // never sees, so nothing else sets the book progress is keyed on.
                    playbackManager.activateBook(lastPlayed.bookId)

                    // Build MediaItems from timeline
                    val resolvedMediaItems =
                        prepareResult.timeline.files.map { file ->
                            MediaItem
                                .Builder()
                                .setMediaId(file.audioFileId)
                                .setUri(file.playbackUri)
                                .setMediaMetadata(
                                    MediaMetadata
                                        .Builder()
                                        .setTitle(prepareResult.bookTitle)
                                        .setArtist(prepareResult.bookAuthor)
                                        .setAlbumTitle(prepareResult.seriesName)
                                        .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                                        .build(),
                                ).build()
                        }

                    // Apply the restored playback speed to ExoPlayer
                    transport.applyResumeSpeed(prepareResult.resumeSpeed)
                    // Resolve start position
                    val startPosition = prepareResult.timeline.resolve(prepareResult.resumePositionMs)

                    completer.set(
                        MediaSession.MediaItemsWithStartPosition(
                            resolvedMediaItems,
                            startPosition.mediaItemIndex,
                            startPosition.positionInFileMs,
                        ),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(e) { "Playback resumption failed" }
                    completer.setException(e)
                }
            }
            "PlaybackResumption"
        }
    }

    // ========== Custom Commands ==========

    /**
     * Seeks the transport player and publishes where it landed.
     *
     * Publishing is not optional bookkeeping. The in-app position is fed by a poll that runs
     * only while audio is playing, so a notification skip taken while paused moved the player
     * and left [PlaybackManager] holding the old position — and the next in-app skip, computed
     * from that stale value, silently undid the one the listener just made.
     *
     * The landing coordinates are the ones just resolved rather than read back from the player,
     * so the published position cannot drift from the seek that produced it.
     */
    private fun seekAndPublish(
        player: Player,
        mediaItemIndex: Int,
        positionInFileMs: Long,
    ) {
        player.seekTo(mediaItemIndex, positionInFileMs)
        playbackManager.updatePositionFromMediaItem(mediaItemIndex, positionInFileMs)
    }

    /**
     * The file-relative fallback taken when no [PlaybackTimeline] is loaded: the seek stays
     * within the current media item, so that item's index is the one to publish against.
     */
    private fun seekAndPublish(
        player: Player,
        positionInFileMs: Long,
    ) {
        player.seekTo(positionInFileMs)
        playbackManager.updatePositionFromMediaItem(player.currentMediaItemIndex, positionInFileMs)
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        // Route transport controls to the TRANSPORT player — the cast player while casting,
        // the raw local ExoPlayer otherwise — so skip/chapter/speed reach the receiver, not
        // the paused local player, and so the seek math below (which resolves book-relative
        // positions via `timeline`) never runs against ChapterWindowPlayer's chapter-relative
        // coordinates. Queue order is identical on both (the index-shift guard in
        // handoffToCast ensures a 1:1 map), so the timeline's book-relative → index/offset
        // math holds unchanged.
        val p =
            transport.activeTransportPlayer() ?: return Futures.immediateFuture(
                SessionResult(SessionResult.RESULT_ERROR_UNKNOWN),
            )
        val chapters = playbackManager.chapters.value
        val timeline = playbackManager.currentTimeline.value

        when (customCommand.customAction) {
            AudiobookNotificationProvider.COMMAND_SKIP_BACK_30 -> {
                // Get book-relative position, subtract 30s, seek
                val currentBookPosition = transport.bookRelativePositionMs()
                val newPosition = (currentBookPosition - 30_000).coerceAtLeast(0)

                // Resolve new position to mediaItemIndex and filePosition
                if (timeline != null) {
                    val resolved = timeline.resolve(newPosition)
                    seekAndPublish(p, resolved.mediaItemIndex, resolved.positionInFileMs)
                } else {
                    // Fallback to simple seek within current file
                    val newFilePosition = (p.currentPosition - 30_000).coerceAtLeast(0)
                    seekAndPublish(p, newFilePosition)
                }
                logger.debug { "Skip back 30s: $currentBookPosition -> $newPosition" }
            }

            AudiobookNotificationProvider.COMMAND_SKIP_FORWARD_30 -> {
                val currentBookPosition = transport.bookRelativePositionMs()
                val maxPosition = timeline?.totalDurationMs ?: p.duration
                val newPosition = (currentBookPosition + 30_000).coerceAtMost(maxPosition)

                if (timeline != null) {
                    val resolved = timeline.resolve(newPosition)
                    seekAndPublish(p, resolved.mediaItemIndex, resolved.positionInFileMs)
                } else {
                    val newFilePosition = (p.currentPosition + 30_000).coerceAtMost(p.duration)
                    seekAndPublish(p, newFilePosition)
                }
                logger.debug { "Skip forward 30s: $currentBookPosition -> $newPosition" }
            }

            AudiobookNotificationProvider.COMMAND_PREV_CHAPTER -> {
                // Shares its mapping with ChapterWindowPlayer.handleSeek's
                // COMMAND_SEEK_TO_PREVIOUS branch — one implementation, same
                // restart-vs-jump threshold everywhere (see previousChapterTarget's KDoc).
                if (chapters.isNotEmpty() && timeline != null) {
                    val target =
                        previousChapterTarget(chapters, transport.bookRelativePositionMs(), timeline.totalDurationMs)
                    val resolved = timeline.resolve(target)
                    seekAndPublish(p, resolved.mediaItemIndex, resolved.positionInFileMs)
                    logger.debug { "Previous chapter target: ${target}ms" }
                }
            }

            AudiobookNotificationProvider.COMMAND_NEXT_CHAPTER -> {
                // Shares its mapping with ChapterWindowPlayer.handleSeek's
                // COMMAND_SEEK_TO_NEXT branch — one implementation everywhere.
                if (chapters.isNotEmpty() && timeline != null) {
                    val target =
                        nextChapterTarget(chapters, transport.bookRelativePositionMs(), timeline.totalDurationMs)
                    val resolved = timeline.resolve(target)
                    seekAndPublish(p, resolved.mediaItemIndex, resolved.positionInFileMs)
                    logger.debug { "Next chapter target: ${target}ms" }
                }
            }

            CustomActions.CYCLE_SPEED -> {
                val currentSpeed = p.playbackParameters.speed
                val newSpeed = CustomActions.getNextSpeed(currentSpeed)
                p.setPlaybackSpeed(newSpeed)
                playbackManager.onSpeedChanged(newSpeed)
                logger.info {
                    "Speed cycled: ${CustomActions.formatSpeed(
                        currentSpeed,
                    )} -> ${CustomActions.formatSpeed(newSpeed)}"
                }
            }

            CustomActions.SEEK_TO_BOOK_POSITION -> {
                // The in-app seek transport (see AndroidPlaybackController.seekTo's KDoc):
                // book-relative because the session player is ChapterWindowPlayer, and a
                // controller-side seek would be reinterpreted chapter-relatively and clamped
                // to the current chapter window.
                val target = args.getLong(CustomActions.EXTRA_BOOK_POSITION_MS, -1L)
                if (target < 0) {
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                }
                if (timeline != null) {
                    val resolved = timeline.resolve(target.coerceIn(0L, timeline.totalDurationMs))
                    seekAndPublish(p, resolved.mediaItemIndex, resolved.positionInFileMs)
                } else {
                    seekAndPublish(p, target)
                }
                logger.debug { "Seek to book position: ${target}ms" }
            }
        }

        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }
}

/** Convert Android's EXTRA_MEDIA_FOCUS string to our MediaFocus enum. */
private fun String.toMediaFocus(): MediaFocus? =
    when (this) {
        MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE -> MediaFocus.ARTIST
        MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE -> MediaFocus.ALBUM
        MediaStore.Audio.Media.ENTRY_CONTENT_TYPE -> MediaFocus.TITLE
        else -> MediaFocus.UNSPECIFIED
    }
