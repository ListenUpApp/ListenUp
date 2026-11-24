package com.calypsan.listenup.client.playback

import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaSession
import com.calypsan.listenup.api.result.getOrNull
import com.calypsan.listenup.api.result.valueOrNull
import com.calypsan.listenup.client.automotive.BrowseTree
import com.calypsan.listenup.client.automotive.BrowseTreeProvider
import com.calypsan.listenup.client.domain.repository.HomeRepository
import com.calypsan.listenup.client.domain.repository.LoadedBookRepository
import com.calypsan.listenup.client.voice.MediaFocus
import com.calypsan.listenup.client.voice.PlaybackIntent
import com.calypsan.listenup.client.voice.VoiceHints
import com.calypsan.listenup.client.voice.VoiceIntentResolver
import com.calypsan.listenup.core.BookId
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

internal sealed interface ResumptionOutcome {
    data class Ready(
        val items: MediaSession.MediaItemsWithStartPosition,
    ) : ResumptionOutcome

    data class Refused(
        val message: String,
    ) : ResumptionOutcome
}

/**
 * Resolves browse/voice search queries, controller-requested [MediaItem]s, and system playback
 * resumption into playable [MediaItem] timelines for [ListenUpSessionCallback].
 */
internal class SessionMediaResolver(
    private val playbackManager: PlaybackManager,
    private val browseTreeProvider: BrowseTreeProvider,
    private val voiceIntentResolver: VoiceIntentResolver,
    private val homeRepository: HomeRepository,
    private val loadedBookRepository: LoadedBookRepository,
    private val transport: PlaybackTransport,
) {
    /** Resolves a search [query] + optional MediaStore [extras] into browse items. */
    suspend fun search(
        query: String,
        extras: Bundle?,
    ): List<MediaItem> {
        val hints = extractVoiceHints(extras)
        logger.debug { "Search hints: ${hints.logLine()}" }

        val intent = voiceIntentResolver.resolve(query, hints)
        logger.info { "Search resolved to: $intent" }

        return when (intent) {
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
    }

    /**
     * Resolves controller-requested [MediaItem]s (voice-search queries and/or book mediaIds)
     * to the actual playable [MediaItem]s Media3 needs, applying the restored playback speed
     * as each book resolves. Shared by [ListenUpSessionCallback.onAddMediaItems] and
     * [ListenUpSessionCallback.onSetMediaItems].
     */
    suspend fun resolveMediaItems(mediaItems: List<MediaItem>): Pair<List<MediaItem>, PlaybackManager.PrepareResult?> {
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
                val prepareResult = prepareForSilentStart(BookId(bookId), "Browse selection")
                if (prepareResult != null) {
                    // Apply the restored playback speed to ExoPlayer
                    transport.applyResumeSpeed(prepareResult.resumeSpeed)
                    resolvedItems.addAll(buildBookMediaItems(prepareResult))
                    lastPrepareResult = prepareResult
                }
            } else {
                // Not a book ID, pass through as-is
                resolvedItems.add(item)
            }
        }

        return resolvedItems to lastPrepareResult
    }

    /** Resolves system playback resumption against the loaded book repository. */
    suspend fun resolveResumption(): ResumptionOutcome {
        // The system resumes the loaded book: with nothing loaded, refuse, so a book the
        // reader closed is not resurrected by the resumption card after a reboot.
        val bookId = loadedBookRepository.loadedBookId()
        if (bookId == null) {
            logger.info { "Nothing is loaded; refusing playback resumption" }
            return ResumptionOutcome.Refused("Nothing is loaded")
        }

        logger.info { "Resuming book: ${bookId.value}" }

        // Prepare playback for the book
        val prepareResult = prepareForSilentStart(bookId, "Resumption")
        if (prepareResult == null) {
            logger.error { "Failed to prepare book for resumption" }
            return ResumptionOutcome.Refused("Failed to prepare book")
        }

        // Same reason as onSetMediaItems: resumption is a play path no screen sees, so nothing
        // else sets the book progress is keyed on; it is also what PlayerSession adopts.
        playbackManager.activateBook(bookId)

        val resolvedMediaItems = buildBookMediaItems(prepareResult)

        // Apply the restored playback speed to ExoPlayer
        transport.applyResumeSpeed(prepareResult.resumeSpeed)
        // Resolve start position
        val startPosition = prepareResult.timeline.resolve(prepareResult.resumePositionMs)

        return ResumptionOutcome.Ready(
            MediaSession.MediaItemsWithStartPosition(
                resolvedMediaItems,
                startPosition.mediaItemIndex,
                startPosition.positionInFileMs,
            ),
        )
    }

    /**
     * Handle voice search from Android Auto / Google Assistant.
     *
     * Extracts search query and extras from MediaItem's requestMetadata,
     * resolves through VoiceIntentResolver, and returns playable media items alongside the
     * resolved book's [PlaybackManager.PrepareResult] (null when nothing resolved).
     */
    private suspend fun handleVoiceSearch(item: MediaItem): Pair<List<MediaItem>, PlaybackManager.PrepareResult?> {
        val searchQuery = item.requestMetadata.searchQuery ?: return emptyList<MediaItem>() to null
        val hints = extractVoiceHints(item.requestMetadata.extras)
        logger.debug { "Voice hints: ${hints.logLine()}" }

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
        val prepareResult = prepareForSilentStart(BookId(bookId), "Voice playback")
        if (prepareResult == null) {
            logger.error { "Failed to prepare book for voice playback: $bookId" }
            return emptyList<MediaItem>() to null
        }

        return buildBookMediaItems(prepareResult) to prepareResult
    }

    /**
     * Prepare [bookId] for a start that has no on-screen surface to report to (Android Auto browse,
     * voice, media-button resumption): the [PlaybackManager.PrepareResult], or null with the choke
     * point's typed refusal or failure logged — a held book included.
     *
     * Another book still playing is paused first, as the in-app switch does before it prepares
     * (`ManagedPlayerEngine.beginLoad`). Preparing hands the manager the new book's timeline while
     * the old one still sounds, and its pause would otherwise arrive after the switch: filed under
     * the new book at the new book's place, with the old book's listening span closed there too and
     * everything since its last periodic save lost. Paused here, on the main thread, the local
     * player reports it at once, while everything still names the outgoing book; a cast receiver
     * that reports late is caught by the recorder, which closes the outgoing span at its own last
     * known position whatever book the pause names.
     */
    private suspend fun prepareForSilentStart(
        bookId: BookId,
        startedBy: String,
    ): PlaybackManager.PrepareResult? {
        silenceOutgoingBook(incoming = bookId)
        return playbackManager.prepareForPlayback(bookId, PlaybackStart.Resume).valueOrNull {
            logger.warn { "$startedBy not started for ${bookId.value}: ${it.code}" }
        }
    }

    private fun silenceOutgoingBook(incoming: BookId) {
        val outgoing = playbackManager.currentBookId.value ?: return
        if (outgoing == incoming) return
        val player = transport.activeTransportPlayer() ?: return
        if (player.playWhenReady) player.pause()
    }

    private fun extractVoiceHints(extras: Bundle?): VoiceHints =
        VoiceHints(
            title = extras?.getString(MediaStore.EXTRA_MEDIA_TITLE),
            artist = extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST),
            album = extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM),
            focus = extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS)?.toMediaFocus(),
        )

    private fun buildBookMediaItems(prepareResult: PlaybackManager.PrepareResult): List<MediaItem> =
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
}

/** Convert Android's EXTRA_MEDIA_FOCUS string to our MediaFocus enum. */
private fun String.toMediaFocus(): MediaFocus? =
    when (this) {
        MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE -> MediaFocus.ARTIST
        MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE -> MediaFocus.ALBUM
        MediaStore.Audio.Media.ENTRY_CONTENT_TYPE -> MediaFocus.TITLE
        else -> MediaFocus.UNSPECIFIED
    }

/** The hints a voice search arrived with, for the debug log; an absent hint reads "none", not "null". */
private fun VoiceHints.logLine(): String =
    "title=${title ?: "none"}, artist=${artist ?: "none"}, focus=${focus ?: "none"}"
