package com.calypsan.listenup.client.domain.usecase.collection

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.AccessMode
import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.domain.model.Library
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.LibraryRepository
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifyNoMoreCalls
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

class CreateCollectionUseCaseTest :
    FunSpec({
        fun library(id: String = "lib-1"): Library =
            Library(
                id = id,
                name = "My Library",
                metadataPrecedence = "audible",
                accessMode = AccessMode.OPEN,
                createdByUserId = null,
                createdAt = 1704067200000L,
                revision = 1L,
            )

        fun collection(
            id: String = "col-1",
            name: String = "Staff Picks",
        ): Collection =
            Collection(
                id = id,
                name = name,
                ownerId = "user-1",
                isInbox = false,
                isSystem = false,
                bookCount = 0,
                callerPermission = SharePermission.Write,
                isOwner = true,
            )

        test("trims the name and creates the collection in the first library") {
            runTest {
                val collectionRepo: CollectionRepository = mock()
                val libraryRepo: LibraryRepository = mock()
                val created = collection(name = "Staff Picks")
                every { libraryRepo.observeAll() } returns flowOf(listOf(library("lib-1")))
                everySuspend { collectionRepo.create(any(), any()) } returns AppResult.Success(created)
                val useCase = CreateCollectionUseCase(collectionRepo, libraryRepo)

                val result = useCase("  Staff Picks  ")

                result.shouldBeInstanceOf<AppResult.Success<Collection>>().data shouldBe created
                verifySuspend { collectionRepo.create("lib-1", "Staff Picks") }
            }
        }

        test("returns validation failure and never touches the repository for a blank name") {
            runTest {
                val collectionRepo: CollectionRepository = mock()
                val libraryRepo: LibraryRepository = mock()
                val useCase = CreateCollectionUseCase(collectionRepo, libraryRepo)

                val result = useCase("   ")

                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.error.shouldBeInstanceOf<ValidationError>()
                failure.message shouldBe "Collection name is required"
                verifyNoMoreCalls(collectionRepo)
            }
        }

        test("returns validation failure when no library is available") {
            runTest {
                val collectionRepo: CollectionRepository = mock()
                val libraryRepo: LibraryRepository = mock()
                every { libraryRepo.observeAll() } returns flowOf(emptyList())
                val useCase = CreateCollectionUseCase(collectionRepo, libraryRepo)

                val result = useCase("Staff Picks")

                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.error.shouldBeInstanceOf<ValidationError>()
                failure.message shouldBe "No library available"
                verifySuspend(mode = VerifyMode.not) { collectionRepo.create(any(), any()) }
            }
        }
    })
