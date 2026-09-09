package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.local.db.SeriesDao
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ImageStagingRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.SeriesEditRepository
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.client.domain.usecase.series.UpdateSeriesUseCase
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [seriesPresentationModule].
 *
 * The whitelist enumerates dependencies this module pulls in but other modules own:
 *
 *  - [SeriesRepository] — owned by `seriesModule`.
 *  - [ImageRepository] — owned by `mediaModule`.
 *  - [PlaybackPositionRepository] — owned by `playbackModule`.
 *  - [UpdateSeriesUseCase] — owned by `seriesModule`.
 *  - [ImageStagingRepository] — owned by `mediaModule`.
 *  - [SeriesEditRepository] — owned by `seriesModule`.
 *  - [SeriesDao] — owned by `persistenceModule`.
 *  - [ErrorBus] — owned by `appCoreModule`.
 */
@OptIn(KoinExperimentalAPI::class)
class SeriesPresentationModuleVerifyTest :
    FunSpec({

        test("seriesPresentationModule wires up against its declared external dependencies") {
            seriesPresentationModule.verify(
                extraTypes =
                    listOf(
                        SeriesRepository::class,
                        ImageRepository::class,
                        PlaybackPositionRepository::class,
                        UpdateSeriesUseCase::class,
                        ImageStagingRepository::class,
                        SeriesEditRepository::class,
                        SeriesDao::class,
                        ErrorBus::class,
                    ),
            )
        }
    })
