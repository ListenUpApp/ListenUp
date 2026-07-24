package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.SyncControl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest

class RefreshedDomainRouterTest :
    FunSpec({

        test("Ping strategy fires the ping when its trigger arrives") {
            runTest {
                var pinged = false
                val router =
                    RefreshedDomainRouter(
                        listOf(presenceDomain(ping = { pinged = true })),
                    )

                val handled = router.dispatch(SyncControl.ActiveSessionsChanged)

                handled shouldBe true
                pinged shouldBe true
            }
        }

        test("Refetch strategy runs the suspend refetch when its trigger arrives") {
            runTest {
                var refetched = false
                val router =
                    RefreshedDomainRouter(
                        listOf(serverInfoDomain(refetch = { refetched = true })),
                    )

                val handled = router.dispatch(SyncControl.ServerInfoChanged)

                handled shouldBe true
                refetched shouldBe true
            }
        }

        test("Refetch failure is swallowed so dispatch never throws") {
            runTest {
                val router =
                    RefreshedDomainRouter(
                        listOf(preferencesDomain(refetch = { error("boom") })),
                    )

                // Must not throw — best-effort contract.
                val handled = router.dispatch(SyncControl.PreferencesChanged)

                handled shouldBe true
            }
        }

        test("Refetch that throws CancellationException propagates out of dispatch") {
            runTest {
                val router =
                    RefreshedDomainRouter(
                        listOf(serverInfoDomain(refetch = { throw CancellationException("cancelled") })),
                    )

                shouldThrow<CancellationException> {
                    router.dispatch(SyncControl.ServerInfoChanged)
                }
            }
        }

        test("an engine/lifecycle control is not claimed by the router") {
            runTest {
                val router =
                    RefreshedDomainRouter(
                        listOf(presenceDomain(ping = {})),
                    )

                router.dispatch(SyncControl.AccessChanged()) shouldBe false
                router.dispatch(SyncControl.LibraryDataChanged) shouldBe false
            }
        }
    })
