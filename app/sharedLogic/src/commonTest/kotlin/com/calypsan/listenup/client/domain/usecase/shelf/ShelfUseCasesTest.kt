package com.calypsan.listenup.client.domain.usecase.shelf

import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.checkIs
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ShelfId
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Tests for Shelf use cases.
 *
 * The repository now returns [AppResult] directly; use cases validate then forward
 * the typed result. Covers validation gates plus the new privacy/reorder surface.
 */
class ShelfUseCasesTest :
    FunSpec({
        // ========== Test Fixtures ==========

        fun createShelf(
            id: String = "shelf-123",
            name: String = "Test Shelf",
            description: String? = null,
            isPrivate: Boolean = false,
        ) = Shelf(
            id = ShelfId(id),
            name = name,
            description = description,
            isPrivate = isPrivate,
            ownerId = "owner-123",
            ownerDisplayName = "Test User",
            bookCount = 0,
            totalDurationSeconds = 0,
            createdAtMs = 1736208000000L,
            updatedAtMs = 1736208000000L,
        )

        // ========== CreateShelfUseCase Tests ==========

        test("create shelf returns success with valid name") {
            runTest {
                val shelfRepository: ShelfRepository = mock()
                val expectedShelf = createShelf(name = "My Reading List")
                everySuspend { shelfRepository.createShelf(any(), any(), any()) } returns AppResult.Success(expectedShelf)
                val useCase = CreateShelfUseCase(shelfRepository)

                val result = useCase(name = "My Reading List", description = null)

                val success = result.shouldBeInstanceOf<AppResult.Success<Shelf>>()
                success.data.name shouldBe "My Reading List"
            }
        }

        test("create shelf calls repository with trimmed name and privacy flag") {
            runTest {
                val shelfRepository: ShelfRepository = mock()
                everySuspend {
                    shelfRepository.createShelf(any(), any(), any())
                } returns AppResult.Success(createShelf())
                val useCase = CreateShelfUseCase(shelfRepository)

                useCase(name = "  Trimmed Name  ", description = null, isPrivate = true)

                verifySuspend { shelfRepository.createShelf("Trimmed Name", null, true) }
            }
        }

        test("create shelf returns validation error for blank name") {
            runTest {
                val shelfRepository: ShelfRepository = mock()
                val useCase = CreateShelfUseCase(shelfRepository)

                val result = useCase(name = "   ", description = null)

                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.error.shouldBeInstanceOf<ValidationError>()
                failure.message shouldBe "Shelf name is required"
            }
        }

        test("create shelf converts empty description to null") {
            runTest {
                val shelfRepository: ShelfRepository = mock()
                everySuspend {
                    shelfRepository.createShelf(any(), any(), any())
                } returns AppResult.Success(createShelf())
                val useCase = CreateShelfUseCase(shelfRepository)

                useCase(name = "Test", description = "   ")

                verifySuspend { shelfRepository.createShelf("Test", null, false) }
            }
        }

        test("create shelf forwards repository failure") {
            runTest {
                val shelfRepository: ShelfRepository = mock()
                everySuspend {
                    shelfRepository.createShelf(any(), any(), any())
                } returns AppResult.Failure(ValidationError(message = "duplicate"))
                val useCase = CreateShelfUseCase(shelfRepository)

                val result = useCase(name = "Test", description = null)

                result.shouldBeInstanceOf<AppResult.Failure>()
            }
        }

        // ========== UpdateShelfUseCase Tests ==========

        test("update shelf returns success with valid name") {
            runTest {
                val shelfRepository: ShelfRepository = mock()
                val expectedShelf = createShelf(name = "Updated Name")
                everySuspend {
                    shelfRepository.updateShelf(any(), any(), any(), any())
                } returns AppResult.Success(expectedShelf)
                val useCase = UpdateShelfUseCase(shelfRepository)

                val result = useCase(shelfId = ShelfId("shelf-123"), name = "Updated Name", description = null)

                val success = result.shouldBeInstanceOf<AppResult.Success<Shelf>>()
                success.data.name shouldBe "Updated Name"
            }
        }

        test("update shelf calls repository with correct parameters and privacy flag") {
            runTest {
                val shelfRepository: ShelfRepository = mock()
                everySuspend {
                    shelfRepository.updateShelf(any(), any(), any(), any())
                } returns AppResult.Success(createShelf())
                val useCase = UpdateShelfUseCase(shelfRepository)

                useCase(shelfId = ShelfId("shelf-456"), name = "New Name", description = "New description", isPrivate = true)

                verifySuspend { shelfRepository.updateShelf(ShelfId("shelf-456"), "New Name", "New description", true) }
            }
        }

        test("update shelf returns validation error for blank name") {
            runTest {
                val shelfRepository: ShelfRepository = mock()
                val useCase = UpdateShelfUseCase(shelfRepository)

                val result = useCase(shelfId = ShelfId("shelf-123"), name = "   ", description = null)

                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.error.shouldBeInstanceOf<ValidationError>()
                failure.message shouldBe "Shelf name is required"
            }
        }

        // ========== DeleteShelfUseCase Tests ==========

        test("delete shelf returns success") {
            runTest {
                val shelfRepository: ShelfRepository = mock()
                everySuspend { shelfRepository.deleteShelf(any()) } returns AppResult.Success(Unit)
                val useCase = DeleteShelfUseCase(shelfRepository)

                val result = useCase(shelfId = ShelfId("shelf-123"))

                checkIs<AppResult.Success<Unit>>(result)
            }
        }

        test("delete shelf calls repository with correct ID") {
            runTest {
                val shelfRepository: ShelfRepository = mock()
                everySuspend { shelfRepository.deleteShelf(any()) } returns AppResult.Success(Unit)
                val useCase = DeleteShelfUseCase(shelfRepository)

                useCase(shelfId = ShelfId("shelf-456"))

                verifySuspend { shelfRepository.deleteShelf(ShelfId("shelf-456")) }
            }
        }

        test("delete shelf forwards repository failure") {
            runTest {
                val shelfRepository: ShelfRepository = mock()
                everySuspend {
                    shelfRepository.deleteShelf(any())
                } returns AppResult.Failure(ValidationError(message = "boom"))
                val useCase = DeleteShelfUseCase(shelfRepository)

                val result = useCase(shelfId = ShelfId("shelf-123"))

                result.shouldBeInstanceOf<AppResult.Failure>()
            }
        }

        // ========== ReorderShelfBooksUseCase Tests ==========

        test("reorder books forwards the new order to the repository") {
            runTest {
                val shelfRepository: ShelfRepository = mock()
                everySuspend { shelfRepository.reorderBooks(any(), any()) } returns AppResult.Success(Unit)
                val useCase = ReorderShelfBooksUseCase(shelfRepository)

                val result = useCase(shelfId = ShelfId("shelf-1"), orderedBookIds = listOf(BookId("b2"), BookId("b1"), BookId("b3")))

                checkIs<AppResult.Success<Unit>>(result)
                verifySuspend { shelfRepository.reorderBooks(ShelfId("shelf-1"), listOf(BookId("b2"), BookId("b1"), BookId("b3"))) }
            }
        }

        test("reorder books returns validation error for empty list") {
            runTest {
                val shelfRepository: ShelfRepository = mock()
                val useCase = ReorderShelfBooksUseCase(shelfRepository)

                val result = useCase(shelfId = ShelfId("shelf-1"), orderedBookIds = emptyList())

                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.error.shouldBeInstanceOf<ValidationError>()
            }
        }

        // ========== AddBooksToShelfUseCase Tests ==========

        test("add books returns validation error for empty list") {
            runTest {
                val shelfRepository: ShelfRepository = mock()
                val useCase = AddBooksToShelfUseCase(shelfRepository)

                val result = useCase(shelfId = ShelfId("shelf-1"), bookIds = emptyList())

                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.error.shouldBeInstanceOf<ValidationError>()
            }
        }

        test("add books forwards to the repository") {
            runTest {
                val shelfRepository: ShelfRepository = mock()
                everySuspend { shelfRepository.addBooksToShelf(any(), any()) } returns AppResult.Success(Unit)
                val useCase = AddBooksToShelfUseCase(shelfRepository)

                val result = useCase(shelfId = ShelfId("shelf-1"), bookIds = listOf(BookId("b1"), BookId("b2")))

                checkIs<AppResult.Success<Unit>>(result)
                verifySuspend { shelfRepository.addBooksToShelf(ShelfId("shelf-1"), listOf(BookId("b1"), BookId("b2"))) }
            }
        }
    })
