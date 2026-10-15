package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.local.db.PlaybackPositionDao
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.test.fake.FakeDownloadRepository

/** A [HomeRepositoryImpl] with nothing downloaded unless a test says otherwise. */
internal fun homeRepositoryOf(
    bookRepository: BookRepository,
    playbackPositionDao: PlaybackPositionDao,
    downloadRepository: DownloadRepository = FakeDownloadRepository(),
): HomeRepositoryImpl =
    HomeRepositoryImpl(
        bookRepository = bookRepository,
        playbackPositionDao = playbackPositionDao,
        downloadRepository = downloadRepository,
    )
