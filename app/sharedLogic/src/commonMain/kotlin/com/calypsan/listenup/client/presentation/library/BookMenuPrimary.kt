package com.calypsan.listenup.client.presentation.library

/** What a book's menu leads with (spec §3.1.2 "Resume/Play · 6h 2m left"). The words are each platform's. */
sealed interface BookMenuPrimary {
    /** Never started: "Play". */
    data object Play : BookMenuPrimary

    /** Started and unfinished: "Resume", with [timeLeftMs] beside it. */
    data class Resume(
        val timeLeftMs: Long,
    ) : BookMenuPrimary

    /** Finished: "Listen again". The shared preparer restarts a finished book from the beginning. */
    data object ListenAgain : BookMenuPrimary
}

/** The menu's first item for a book whose card says [status]. */
fun bookMenuPrimary(status: BookCardStatus?): BookMenuPrimary =
    when (status) {
        is BookCardStatus.InProgress -> BookMenuPrimary.Resume(status.timeLeftMs)
        is BookCardStatus.Finished -> BookMenuPrimary.ListenAgain
        is BookCardStatus.NotStarted, null -> BookMenuPrimary.Play
    }
