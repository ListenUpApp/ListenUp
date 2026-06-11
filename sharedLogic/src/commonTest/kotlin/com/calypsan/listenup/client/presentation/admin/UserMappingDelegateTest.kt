package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.ABSImportApiContract
import com.calypsan.listenup.client.data.remote.UserSearchResult
import com.calypsan.listenup.client.presentation.admin.absimport.UserMappingDelegate
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class UserMappingDelegateTest :
    FunSpec({
        lateinit var state: MutableStateFlow<ABSImportUiState>
        lateinit var absImportApi: ABSImportApiContract
        lateinit var errorBus: ErrorBus

        fun buildDelegate(scope: TestScope): UserMappingDelegate {
            state = MutableStateFlow<ABSImportUiState>(ABSImportUiState.Ready())
            absImportApi = mock()
            errorBus = ErrorBus()
            return UserMappingDelegate(
                scope = scope,
                state = state,
                errorBus = errorBus,
                absImportApi = absImportApi,
            )
        }

        fun ready() = state.value as ABSImportUiState.Ready

        test("updateUserSearchQuery populates userSearchResults when api returns hits") {
            runTest(StandardTestDispatcher()) {
                val delegate = buildDelegate(this)
                val hit =
                    UserSearchResult(
                        id = "user1",
                        email = "ada@example.com",
                        displayName = "Ada",
                        firstName = "Ada",
                        lastName = "Lovelace",
                    )
                everySuspend { absImportApi.searchUsers(any(), any()) } returns AppResult.Success(listOf(hit))

                delegate.updateUserSearchQuery("ada")
                advanceUntilIdle()

                ready().userSearchResults shouldHaveSize 1
                ready().userSearchResults.first().id shouldBe "user1"
                ready().isSearchingUsers shouldBe false
            }
        }

        test("selectUser populates userMappings and selectedUserDisplays and clears search state") {
            runTest(StandardTestDispatcher()) {
                val delegate = buildDelegate(this)

                delegate.selectUser(
                    absUserId = "abs-1",
                    userId = "user1",
                    email = "ada@example.com",
                    displayName = "Ada Lovelace",
                )
                advanceUntilIdle()

                val r = ready()
                r.userMappings shouldContainKey "abs-1"
                r.userMappings["abs-1"] shouldBe "user1"
                r.selectedUserDisplays shouldContainKey "abs-1"
                r.selectedUserDisplays["abs-1"]?.userId shouldBe "user1"
                r.selectedUserDisplays["abs-1"]?.email shouldBe "ada@example.com"
                r.activeSearchAbsUserId.shouldBeNull()
            }
        }

        test("clearUserMapping removes mapping and display after selectUser") {
            runTest(StandardTestDispatcher()) {
                val delegate = buildDelegate(this)

                delegate.selectUser(
                    absUserId = "abs-1",
                    userId = "user1",
                    email = "ada@example.com",
                    displayName = null,
                )
                advanceUntilIdle()

                // precondition: mapping is present
                ready().userMappings shouldContainKey "abs-1"

                delegate.clearUserMapping("abs-1")

                val r = ready()
                r.userMappings shouldNotContainKey "abs-1"
                r.selectedUserDisplays shouldNotContainKey "abs-1"
            }
        }

        test("updateUserSearchQuery with short query clears results without searching") {
            runTest(StandardTestDispatcher()) {
                val delegate = buildDelegate(this)

                // prime with a result first
                state.value =
                    ABSImportUiState.Ready(
                        userSearchResults =
                            listOf(
                                UserSearchResult("u1", "e@e.com", "E", "E", ""),
                            ),
                    )

                delegate.updateUserSearchQuery("a") // length < 2
                advanceUntilIdle()

                ready().userSearchResults shouldHaveSize 0
                ready().isSearchingUsers shouldBe false
            }
        }

        test("activateUserSearch sets activeSearchAbsUserId and clears prior search state") {
            runTest(StandardTestDispatcher()) {
                val delegate = buildDelegate(this)

                delegate.activateUserSearch("abs-2")

                val r = ready()
                r.activeSearchAbsUserId shouldBe "abs-2"
                r.userSearchQuery shouldBe ""
                r.userSearchResults shouldHaveSize 0
            }
        }

        test("deactivateUserSearch clears activeSearchAbsUserId") {
            runTest(StandardTestDispatcher()) {
                val delegate = buildDelegate(this)

                delegate.activateUserSearch("abs-2")
                delegate.deactivateUserSearch()

                ready().activeSearchAbsUserId.shouldBeNull()
            }
        }

        test("selectedUserDisplays entry has displayName when provided") {
            runTest(StandardTestDispatcher()) {
                val delegate = buildDelegate(this)

                delegate.selectUser(
                    absUserId = "abs-3",
                    userId = "user3",
                    email = "grace@example.com",
                    displayName = "Grace Hopper",
                )
                advanceUntilIdle()

                ready().selectedUserDisplays["abs-3"]?.displayName shouldBe "Grace Hopper"
            }
        }
    })
