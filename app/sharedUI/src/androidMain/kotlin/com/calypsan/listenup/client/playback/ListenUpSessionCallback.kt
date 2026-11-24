package com.calypsan.listenup.client.playback

import android.content.Context
import android.os.Bundle
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.calypsan.listenup.client.automotive.AutoBrowseErrors
import com.calypsan.listenup.client.automotive.BrowseTreeProvider
import com.calypsan.listenup.client.automotive.CoverUri
import com.calypsan.listenup.client.automotive.CustomActions
import com.calypsan.listenup.client.automotive.browseNeedsSignIn
import com.calypsan.listenup.client.automotive.isLastPage
import com.calypsan.listenup.client.automotive.paginate
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.HomeRepository
import com.calypsan.listenup.client.domain.repository.LoadedBookRepository
import com.calypsan.listenup.client.localization.SystemStrings
import com.calypsan.listenup.client.localization.SystemStringsHolder
import com.calypsan.listenup.client.voice.VoiceIntentResolver
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
    browseTreeProvider: BrowseTreeProvider,
    voiceIntentResolver: VoiceIntentResolver,
    homeRepository: HomeRepository,
    private val authSession: AuthSession,
    loadedBookRepository: LoadedBookRepository,
    private val serviceScope: CoroutineScope,
    transport: PlaybackTransport,
    private val uriPermissionGranter: UriPermissionGranter,
    private val strings: SystemStringsHolder,
    private val skipIntervals: SkipIntervalsHolder,
) : MediaLibrarySession.Callback {
    private val browseTree = browseTreeProvider

    private val mediaResolver =
        SessionMediaResolver(
            playbackManager = playbackManager,
            browseTreeProvider = browseTreeProvider,
            voiceIntentResolver = voiceIntentResolver,
            homeRepository = homeRepository,
            loadedBookRepository = loadedBookRepository,
            transport = transport,
        )

    private val customCommandHandler =
        SessionCustomCommandHandler(
            playbackManager = playbackManager,
            transport = transport,
            skipIntervals = skipIntervals,
        )

    /** Catalog snapshot for this call. Read per use, so a locale change lands on the next one. */
    private val copy: SystemStrings get() = strings.current

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val customCommands =
            AudiobookNotificationProvider.getCustomCommands() +
                listOf(CustomActions.cycleSpeedCommand(), CustomActions.seekToBookPositionCommand())

        val trust = session.classifyController(controller)
        // controllerVersion is logged because the whole signed-out Auto design rests on it being
        // LEGACY_CONTROLLER_VERSION: Media3 replicates a browse error into the platform playback
        // state for legacy browsers ONLY. If a head unit ever connects as a Media3 controller
        // instead, the sign-in wall silently stops being replicated and nothing else says so.
        logger.debug {
            "onConnect from ${controller.packageName} classified as $trust " +
                "(controllerVersion=${controller.controllerVersion})"
        }
        if (mayAccessCoverArt(trust)) {
            grantCoverArtAccess(controller.packageName)
        }
        return session.buildConnectionResultFor(
            controller = controller,
            trust = trust,
            customSessionCommands = customCommands,
            mediaButtons =
                mediaButtonsFor(
                    trust = trust,
                    backwardSec = skipIntervals.backwardSec,
                    forwardSec = skipIntervals.forwardSec,
                    words = copy,
                ),
        )
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
        // ⛔ The root NEVER gates on sign-in, however signed out we are. A root that errors leaves
        // the browser with no content hierarchy, and the head unit answers "ListenUp doesn't seem
        // to be working right now" — the app reads as broken rather than as signed out, and there
        // is no sign-in prompt left to act on. It cost a Play policy rejection (Auto App Quality:
        // "app does not load in the Android Auto environment"), reachable on any fresh install,
        // and guaranteed for a reviewer: ListenUp is self-hosted, so they have no server to sign
        // in to. Google's guidance is explicit that onGetRoot returns a non-null root quickly and
        // that authentication does not belong in it.
        //
        // The gate is not lost, it sits one level down: onGetChildren still answers with the typed
        // signed-out error carrying the sign-in action, which is the surface Auto can actually
        // render as a prompt. Both halves are pinned in ListenUpSessionCallbackTest.
        logger.debug { "onGetLibraryRoot" }
        val root = browseTree.getRoot()
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
                LibraryResult.ofError(AutoBrowseErrors.signedOutError(context, copy)),
            )
        }

        return CallbackToFutureAdapter.getFuture { completer ->
            serviceScope.launch {
                try {
                    val children = browseTree.getChildren(parentId)
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

        // Gated on the same predicate as the browse tree (#1245): resolving a single item is
        // another way into the library, and one that a head unit can reach from a cached mediaId
        // without walking the tree at all. Room is usually empty when genuinely signed out, so
        // this used to fail soft as "no such item" — an honest wall beats a silent dead end.
        if (browseNeedsSignIn(authSession.authState.value)) {
            logger.debug { "onGetItem: signed out — returning auth error" }
            return Futures.immediateFuture(
                LibraryResult.ofError(AutoBrowseErrors.signedOutError(context, copy)),
            )
        }

        return CallbackToFutureAdapter.getFuture { completer ->
            serviceScope.launch {
                try {
                    val item = browseTree.getItem(mediaId)
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

        // Voice search is a third door into the library, so it carries the same gate (#1245).
        // Returning the typed error rather than zero results is what makes the refusal legible:
        // "no results for The Hobbit" reads as a missing book, not as a missing session.
        if (browseNeedsSignIn(authSession.authState.value)) {
            logger.debug { "onSearch: signed out — returning auth error" }
            return Futures.immediateFuture(
                LibraryResult.ofError(AutoBrowseErrors.signedOutError(context, copy)),
            )
        }

        // Perform search asynchronously
        serviceScope.launch {
            try {
                val items = mediaResolver.search(query, params?.extras)
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

        // Gated alongside onSearch (#1245): the session can lapse between accepting a query and
        // the head unit collecting its results, and a stale cache entry must not outlive the
        // session that produced it.
        if (browseNeedsSignIn(authSession.authState.value)) {
            logger.debug { "onGetSearchResult: signed out — returning auth error" }
            return Futures.immediateFuture(
                LibraryResult.ofError(AutoBrowseErrors.signedOutError(context, copy)),
            )
        }

        val items = searchResultsCache[query].orEmpty()
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
     * Called when Android Auto adds media items to the queue.
     *
     * Resolves book IDs from media items and prepares full playback timeline via
     * [SessionMediaResolver.resolveMediaItems]. Also handles voice search via
     * `requestMetadata.searchQuery` (Media3 pattern).
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
                    val (resolvedItems, _) = mediaResolver.resolveMediaItems(mediaItems)
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
                    val (resolvedItems, prepareResult) = mediaResolver.resolveMediaItems(mediaItems)

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
     * start once resolved. Our preparation work — reading this device's loaded book and resolving
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
                    when (val outcome = mediaResolver.resolveResumption()) {
                        is ResumptionOutcome.Ready -> completer.set(outcome.items)
                        is ResumptionOutcome.Refused -> completer.setException(IllegalStateException(outcome.message))
                    }
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

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> = Futures.immediateFuture(customCommandHandler.handle(customCommand, args))
}
