package com.calypsan.listenup.client.localization

import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.car_back_seconds
import listenup.composeapp.generated.resources.car_book_subtitle
import listenup.composeapp.generated.resources.car_by_author
import listenup.composeapp.generated.resources.car_by_series
import listenup.composeapp.generated.resources.car_continue_listening
import listenup.composeapp.generated.resources.car_downloaded
import listenup.composeapp.generated.resources.car_forward_seconds
import listenup.composeapp.generated.resources.car_library
import listenup.composeapp.generated.resources.car_sign_in_action
import listenup.composeapp.generated.resources.car_sign_in_message
import listenup.composeapp.generated.resources.player_cast_session_lost
import listenup.composeapp.generated.resources.player_cast_start_failed
import listenup.composeapp.generated.resources.player_cast_unsupported_format
import listenup.composeapp.generated.resources.player_chapter_of
import listenup.composeapp.generated.resources.player_chapter_remaining
import listenup.composeapp.generated.resources.player_next_chapter
import listenup.composeapp.generated.resources.player_pause
import listenup.composeapp.generated.resources.player_play
import listenup.composeapp.generated.resources.player_playing
import listenup.composeapp.generated.resources.player_previous_chapter
import listenup.composeapp.generated.resources.player_refusal_title
import listenup.composeapp.generated.resources.player_skip_backward
import listenup.composeapp.generated.resources.player_skip_forward
import listenup.composeapp.generated.resources.player_speed
import listenup.composeapp.generated.resources.player_unknown_book
import org.jetbrains.compose.resources.getString

/**
 * The strings ListenUp hands to the platform — Android Auto browse nodes, the media notification's
 * action names, Media3 session errors, playback toasts (#1246).
 *
 * ## Why this exists at all
 *
 * The rest of the UI reads the generated catalog through Compose's `stringResource`, and non-Compose
 * *suspend* code reads it through `getString` (see `PushNotificationRenderer`). Neither works here:
 * Media3 asks for these strings from plain synchronous callbacks — `createNotification`, `onConnect`,
 * `onGetLibraryRoot` — that cannot suspend and must not block. So the catalog is resolved once, off
 * the service's own scope, into this immutable snapshot, which those callbacks then read as a field.
 *
 * ## The fallback is deliberate, not a shortcut
 *
 * [ENGLISH_FALLBACK] carries the same copy the catalog does, and is what a caller sees in the
 * window between the service starting and the first load completing — a car can connect and browse
 * within milliseconds of `onCreate`. A blank action button is a worse answer than an untranslated
 * one, so the snapshot is never null and never empty. The catalog remains the source of truth: the
 * fallback exists to be replaced, and is replaced on every start.
 */
data class SystemStrings(
    /** Auto browse: the in-progress shelf. */
    val carContinueListening: String,
    /** Auto browse: the library node. */
    val carLibrary: String,
    /** Auto browse: offline-available books. */
    val carDownloaded: String,
    /** Auto browse: the series index. */
    val carBySeries: String,
    /** Auto browse: the author index. */
    val carByAuthor: String,
    /** Auto browse: a book row's second line, `%1$s` authors and `%2$s` time remaining. */
    val carBookSubtitle: String,
    /** Auto: the label on the sign-in resolution action a head unit offers. */
    val carSignInAction: String,
    /** Auto: the message shown when browse is walled off pending sign-in. */
    val carSignInMessage: String,
    /**
     * Auto: the compact custom-layout label for skipping back, `%1$s` the configured seconds.
     *
     * A format string, not finished copy: the number is the user's synced setting, which can
     * change while a car is connected. `String.format` it at the render site.
     */
    val carBack: String,
    /** Auto: the compact custom-layout label for skipping forward, `%1$s` the configured seconds. */
    val carForward: String,
    /** Notification action: previous chapter. */
    val playerPreviousChapter: String,
    /** Notification action: next chapter. */
    val playerNextChapter: String,
    /**
     * Notification action: skip backwards, `%1$s` the configured seconds.
     *
     * A format string for the same reason as [carBack] — the number belongs to the user.
     */
    val playerSkipBackward: String,
    /** Notification action: skip forwards, `%1$s` the configured seconds. */
    val playerSkipForward: String,
    /** Notification action: resume. */
    val playerPlay: String,
    /** Notification action: pause. */
    val playerPause: String,
    /** Auto custom action: cycle the playback speed. */
    val playerSpeed: String,
    /** Notification headline when neither the book nor the session names a title. */
    val playerUnknownBook: String,
    /** Notification subtitle when no chapter information is available. */
    val playerPlaying: String,
    /** Generic chapter label, `%1$s` position and `%2$s` total. */
    val playerChapterOf: String,
    /** Notification subtitle, `%1$s` chapter label and `%2$s` time remaining. */
    val playerChapterRemaining: String,
    /** Headline of the notice posted when the platform refuses to start playback. */
    val playerRefusalTitle: String,
    /** Toast shown when a cast handoff cannot be prepared. */
    val playerCastStartFailed: String,
    /** Toast shown when a book's format cannot be cast. */
    val playerCastUnsupportedFormat: String,
    /** Toast shown when the cast session drops while a handoff was in flight. */
    val playerCastSessionLost: String,
) {
    companion object {
        /**
         * The pre-load snapshot — see the class KDoc. Kept identical to `en.json`; if the two ever
         * disagree, the catalog wins the moment [loadSystemStrings] lands, which is within
         * milliseconds of the service starting.
         */
        val ENGLISH_FALLBACK =
            SystemStrings(
                carContinueListening = "Continue Listening",
                carLibrary = "Library",
                carDownloaded = "Downloaded",
                carBySeries = "By Series",
                carByAuthor = "By Author",
                carBookSubtitle = "%1\$s - %2\$s",
                carSignInAction = "Sign in to ListenUp",
                carSignInMessage = "Sign in to ListenUp on your phone.",
                carBack = "Back %1\$ss",
                carForward = "Forward %1\$ss",
                playerPreviousChapter = "Previous chapter",
                playerNextChapter = "Next chapter",
                playerSkipBackward = "Skip backward %1\$s seconds",
                playerSkipForward = "Skip forward %1\$s seconds",
                playerPlay = "Play",
                playerPause = "Pause",
                playerSpeed = "Speed",
                playerUnknownBook = "Unknown Book",
                playerPlaying = "Playing...",
                playerChapterOf = "Chapter %1\$s of %2\$s",
                playerChapterRemaining = "%1\$s • %2\$s left",
                playerRefusalTitle = "Couldn't start playback",
                playerCastStartFailed = "Couldn't start casting.",
                playerCastUnsupportedFormat = "This book's format can't be cast.",
                playerCastSessionLost = "Casting stopped — playing on this device instead.",
            )
    }
}

/**
 * Resolves every [SystemStrings] value from the generated catalog in the process's current locale.
 *
 * Suspends, so it runs off a caller's scope rather than inside a Media3 callback. Call it again
 * after a configuration change — the snapshot is immutable, so a stale one keeps serving the old
 * locale indefinitely.
 */
suspend fun loadSystemStrings(): SystemStrings =
    SystemStrings(
        carContinueListening = getString(Res.string.car_continue_listening),
        carLibrary = getString(Res.string.car_library),
        carDownloaded = getString(Res.string.car_downloaded),
        carBySeries = getString(Res.string.car_by_series),
        carByAuthor = getString(Res.string.car_by_author),
        carBookSubtitle = getString(Res.string.car_book_subtitle),
        carSignInAction = getString(Res.string.car_sign_in_action),
        carSignInMessage = getString(Res.string.car_sign_in_message),
        carBack = getString(Res.string.car_back_seconds),
        carForward = getString(Res.string.car_forward_seconds),
        playerPreviousChapter = getString(Res.string.player_previous_chapter),
        playerNextChapter = getString(Res.string.player_next_chapter),
        // Deliberately NOT resolved with an argument: the seconds are read at render time from
        // the live setting, so the catalog value is carried through as a format string.
        playerSkipBackward = getString(Res.string.player_skip_backward),
        playerSkipForward = getString(Res.string.player_skip_forward),
        playerPlay = getString(Res.string.player_play),
        playerPause = getString(Res.string.player_pause),
        playerSpeed = getString(Res.string.player_speed),
        playerUnknownBook = getString(Res.string.player_unknown_book),
        playerPlaying = getString(Res.string.player_playing),
        playerChapterOf = getString(Res.string.player_chapter_of),
        playerChapterRemaining = getString(Res.string.player_chapter_remaining),
        playerRefusalTitle = getString(Res.string.player_refusal_title),
        playerCastStartFailed = getString(Res.string.player_cast_start_failed),
        playerCastUnsupportedFormat = getString(Res.string.player_cast_unsupported_format),
        playerCastSessionLost = getString(Res.string.player_cast_session_lost),
    )

/**
 * The mutable holder the platform surfaces read from.
 *
 * A single Koin-scoped instance, so the `PlaybackService`-built collaborators and the
 * Koin-built `BrowseTreeProvider` see the same snapshot — and one [refresh] updates every
 * surface at once. Reads happen on Media3's binder threads and writes on the service scope,
 * hence `@Volatile`; the value is an immutable data class, so a reader either sees the whole
 * old snapshot or the whole new one, never a half-swapped mixture.
 */
class SystemStringsHolder {
    @Volatile
    var current: SystemStrings = SystemStrings.ENGLISH_FALLBACK
        private set

    /** Re-resolves the catalog in the current locale. Call at service start and on locale change. */
    suspend fun refresh() {
        current = loadSystemStrings()
    }
}
