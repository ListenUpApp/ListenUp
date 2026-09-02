package com.calypsan.listenup.client.domain.usecase.collection

import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifyNoMoreCalls
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class AddBooksToCollectionUseCaseTest :
    FunSpec({
        test("adds all books to collection in order and returns how many it added") {
            runTest {
                val repo: CollectionRepository = mock()
                everySuspend { repo.addBook(any(), any()) } returns AppResult.Success(true)
                val useCase = AddBooksToCollectionUseCase(repo)

                val result = useCase("col-1", listOf("book1", "book2", "book3"))

                result shouldBe AppResult.Success(3)
                verifySuspend(mode = VerifyMode.exhaustiveOrder) {
                    repo.addBook("col-1", "book1")
                    repo.addBook("col-1", "book2")
                    repo.addBook("col-1", "book3")
                }
            }
        }

        test("counts only the books the collection did not already hold") {
            runTest {
                val repo: CollectionRepository = mock()
                everySuspend { repo.addBook("col-1", "book1") } returns AppResult.Success(true)
                everySuspend { repo.addBook("col-1", "book2") } returns AppResult.Success(false)
                everySuspend { repo.addBook("col-1", "book3") } returns AppResult.Success(true)
                val useCase = AddBooksToCollectionUseCase(repo)

                val result = useCase("col-1", listOf("book1", "book2", "book3"))

                result shouldBe AppResult.Success(2)
            }
        }

        test("returns validation failure and never calls repository for empty book list") {
            runTest {
                val repo: CollectionRepository = mock()
                val useCase = AddBooksToCollectionUseCase(repo)

                val result = useCase("col-1", emptyList())

                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.error.shouldBeInstanceOf<ValidationError>()
                failure.message shouldBe "At least one book must be selected"
                verifyNoMoreCalls(repo)
            }
        }

        test("returns failure immediately when second book fails and does not attempt third book") {
            runTest {
                val repo: CollectionRepository = mock()
                val bookTwoFailure = AppResult.Failure(ValidationError(message = "Server error"))
                everySuspend { repo.addBook("col-1", "book1") } returns AppResult.Success(true)
                everySuspend { repo.addBook("col-1", "book2") } returns bookTwoFailure
                val useCase = AddBooksToCollectionUseCase(repo)

                val result = useCase("col-1", listOf("book1", "book2", "book3"))

                result shouldBe bookTwoFailure
                verifySuspend { repo.addBook("col-1", "book1") }
                verifySuspend { repo.addBook("col-1", "book2") }
                verifyNoMoreCalls(repo)
            }
        }
    })
