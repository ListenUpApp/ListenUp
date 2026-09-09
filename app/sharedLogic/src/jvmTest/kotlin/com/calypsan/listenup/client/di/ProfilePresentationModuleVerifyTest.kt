package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.local.db.PublicProfileDao
import com.calypsan.listenup.client.domain.repository.ProfileEditRepository
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.repository.UserProfileRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [profilePresentationModule].
 *
 * The whitelist enumerates dependencies this module pulls in but other modules own:
 *
 *  - [PublicProfileDao] — owned by `persistenceModule`.
 *  - [ShelfRepository] — owned by `shelfModule`.
 *  - [UserRepository] — owned by `socialModule`.
 *  - [ProfileEditRepository] — owned by `socialModule`.
 *  - [UserProfileRepository] — owned by `socialModule`.
 */
@OptIn(KoinExperimentalAPI::class)
class ProfilePresentationModuleVerifyTest :
    FunSpec({

        test("profilePresentationModule wires up against its declared external dependencies") {
            profilePresentationModule.verify(
                extraTypes =
                    listOf(
                        PublicProfileDao::class,
                        ShelfRepository::class,
                        UserRepository::class,
                        ProfileEditRepository::class,
                        UserProfileRepository::class,
                    ),
            )
        }
    })
