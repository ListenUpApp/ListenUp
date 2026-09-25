package com.calypsan.listenup.client.domain.usecase.auth

import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.core.ServerUrl
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Adopting a server is where the app learns whether it is the SAME server it already mirrors. A
 * different one must start from a clean local library: on 2026-09-15 a device switched servers
 * kept the old server's books, genres and sync cursors, the new server's events then failed on
 * foreign keys against the old rows, and the library showed "No audiobooks yet" over 13 books.
 */
class AdoptServerUseCaseTest :
    FunSpec({
        class Rig(
            previousId: String?,
            libraryFrom: String? = previousId,
        ) {
            val events = mutableListOf<String>()
            var connectedId: String? = previousId
            var libraryServerId: String? = libraryFrom
            val config =
                mock<ServerConfig> {
                    everySuspend { getConnectedServerId() } calls { connectedId }
                    everySuspend { getLibraryServerId() } calls { libraryServerId }
                    everySuspend { setServerUrl(any()) } calls { (url: ServerUrl) -> events += "url:${url.value}" }
                    everySuspend { setConnectedServerId(any()) } calls { (id: String?) ->
                        connectedId = id
                        events += "id:$id"
                    }
                    everySuspend { setLibraryServerId(any()) } calls { (id: String) -> libraryServerId = id }
                }
            val adopt = AdoptServerUseCase(config, localSignOut = { events += "sign-out" })
        }

        test("a different server signs out locally BEFORE it is adopted") {
            runTest {
                val rig = Rig(previousId = "server-a")

                rig.adopt(url = "http://b.local:8080", instanceId = "server-b")

                rig.events shouldBe listOf("sign-out", "url:http://b.local:8080", "id:server-b")
            }
        }

        test("Change Server forgot the connection, but a different server still starts clean") {
            runTest {
                // The Login page's Change Server clears the connected id (it drives IP-follow, and a
                // disconnected app must not relocate back to the old server). The library on the
                // device still came from server A — that, not the connection, decides.
                val rig = Rig(previousId = null, libraryFrom = "server-a")

                rig.adopt(url = "http://b.local:8080", instanceId = "server-b")

                rig.events shouldBe listOf("sign-out", "url:http://b.local:8080", "id:server-b")
                rig.libraryServerId shouldBe "server-b"
            }
        }

        test("an install from before the library's origin was recorded falls back to the connection") {
            runTest {
                val rig = Rig(previousId = "server-a", libraryFrom = null)

                rig.adopt(url = "http://b.local:8080", instanceId = "server-b")

                rig.events.first() shouldBe "sign-out"
                rig.libraryServerId shouldBe "server-b"
            }
        }

        test("the same server at a new address keeps everything") {
            runTest {
                val rig = Rig(previousId = "server-a")

                rig.adopt(url = "http://10.0.0.9:8080", instanceId = "server-a")

                rig.events shouldBe listOf("url:http://10.0.0.9:8080", "id:server-a")
            }
        }

        test("a first connection has nothing to clear") {
            runTest {
                val rig = Rig(previousId = null)

                rig.adopt(url = "http://a.local:8080", instanceId = "server-a")

                rig.events shouldBe listOf("url:http://a.local:8080", "id:server-a")
            }
        }

        test("an unidentified server is adopted without a wipe and without forgetting the old identity") {
            runTest {
                // An invite link whose server could not be verified: the claim must not be blocked,
                // and guessing "different" would wipe a library that may well be the same server.
                val rig = Rig(previousId = "server-a")

                rig.adopt(url = "http://a.local:8080", instanceId = null)

                rig.events shouldBe listOf("url:http://a.local:8080")
                rig.connectedId shouldBe "server-a"
            }
        }
    })
