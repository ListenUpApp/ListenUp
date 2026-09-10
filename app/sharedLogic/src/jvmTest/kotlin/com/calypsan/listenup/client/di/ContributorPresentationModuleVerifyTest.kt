package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.local.db.ContributorAliasDao
import com.calypsan.listenup.client.data.local.db.ContributorDao
import com.calypsan.listenup.client.domain.repository.ContributorEditRepository
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ImageStagingRepository
import com.calypsan.listenup.client.domain.repository.MetadataRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.client.domain.usecase.contributor.DeleteContributorUseCase
import com.calypsan.listenup.client.domain.usecase.contributor.UpdateContributorUseCase
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [contributorPresentationModule].
 *
 * The whitelist enumerates dependencies this module pulls in but other modules own:
 *
 *  - [ContributorRepository] — owned by `contributorModule`.
 *  - [PlaybackPositionRepository] — owned by `playbackModule`.
 *  - [SeriesRepository] — owned by `seriesModule`.
 *  - [DeleteContributorUseCase] — owned by `contributorModule`.
 *  - [UpdateContributorUseCase] — owned by `contributorModule`.
 *  - [ImageRepository] — owned by `mediaModule`.
 *  - [ImageStagingRepository] — owned by `mediaModule`.
 *  - [ContributorEditRepository] — owned by `contributorModule`.
 *  - [ContributorAliasDao] — owned by `persistenceModule`.
 *  - [ContributorDao] — owned by `persistenceModule`.
 *  - [MetadataRepository] — owned by `metadataModule`.
 *  - [ErrorBus] — owned by `appCoreModule`.
 */
@OptIn(KoinExperimentalAPI::class)
class ContributorPresentationModuleVerifyTest :
    FunSpec({

        test("contributorPresentationModule wires up against its declared external dependencies") {
            contributorPresentationModule.verify(
                extraTypes =
                    listOf(
                        ContributorRepository::class,
                        PlaybackPositionRepository::class,
                        SeriesRepository::class,
                        DeleteContributorUseCase::class,
                        UpdateContributorUseCase::class,
                        ImageRepository::class,
                        ImageStagingRepository::class,
                        ContributorEditRepository::class,
                        ContributorAliasDao::class,
                        ContributorDao::class,
                        MetadataRepository::class,
                        ErrorBus::class,
                    ),
            )
        }
    })
