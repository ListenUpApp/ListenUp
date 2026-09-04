package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.GenreRepository
import com.calypsan.listenup.client.domain.repository.MoodRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [browsePresentationModule]. Per the architecture rubric every leaf Koin module
 * is covered by a `module.verify()` test in commonTest. The whitelist enumerates dependencies that
 * the facet-browse ViewModels pull in but other modules own:
 *
 *  - [GenreRepository] — owned by `genreTagModule` (pulled in by `GenreDestinationViewModel`).
 *  - [TagRepository] — owned by `genreTagModule` (pulled in by `BrowseFacetViewModel`).
 *  - [MoodRepository] — owned by `genreTagModule` (pulled in by `BrowseFacetViewModel`).
 *  - [BookRepository] — owned by `bookModule` (pulled in by `BrowseFacetViewModel`).
 *  - [ErrorBus] — owned by `appCoreModule`.
 */
@OptIn(KoinExperimentalAPI::class)
class BrowsePresentationModuleVerifyTest :
    FunSpec({

        test("browsePresentationModule wires up against its declared external dependencies") {
            browsePresentationModule.verify(
                extraTypes =
                    listOf(
                        GenreRepository::class,
                        TagRepository::class,
                        MoodRepository::class,
                        BookRepository::class,
                        ErrorBus::class,
                    ),
            )
        }
    })
