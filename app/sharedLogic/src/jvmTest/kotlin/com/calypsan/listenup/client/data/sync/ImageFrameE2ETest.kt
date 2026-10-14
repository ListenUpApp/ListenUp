package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.api.dto.image.ImageFraming
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.sync.testing.withClientSyncEngineAgainstServer
import com.calypsan.listenup.core.ContributorId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeout

private val ROUND_TRIP = 30.seconds

/**
 * Tier 3: a frame set through the client repository lands in Room first, drains through the outbox to the in-process
 * server, and survives the echo; when another device then replaces the photo, the server clears the frame and this
 * device converges on the echo (spec §5.4).
 */
class ImageFrameE2ETest :
    FunSpec({
        test("a frame set here reaches the server and survives the echo; another device's new photo clears it here") {
            withClientSyncEngineAgainstServer {
                val id = serverContributorRepository.resolveOrCreate("Ray Porter", sortName = null)
                serverContributorRepository.upsert(serverContributorRepository.findById(id.value)!!.copy(imagePath = "contributors/abc.jpg"))
                engine.start(currentUserId = "u1")
                withTimeout(ROUND_TRIP) {
                    while (clientDatabase.contributorDao().getById(id.value)?.imagePath != "contributors/abc.jpg") {
                        // Delivery latency is non-deterministic; poll the real row.
                    }
                }

                val framing = ImageFraming("abc", ImageFrame(0.5, 0.2, 1.5))
                contributorEditRepository.setImageFrame(ContributorId(id.value), framing.frame).shouldBeInstanceOf<AppResult.Success<Unit>>()
                clientDatabase.contributorDao().getById(id.value)!!.imageFrame shouldBe framing // Room first

                withTimeout(ROUND_TRIP) {
                    while (serverContributorRepository.findById(id.value)?.imageFrame != framing) {
                        // The outbox drains to the server.
                    }
                }
                // Wait for the echo of that write to land here, then check it kept the frame: asserting any sooner would
                // read the optimistic write and pass even if the echo dropped the frame.
                val echoed = serverContributorRepository.findById(id.value)!!.revision
                withTimeout(ROUND_TRIP) {
                    while ((clientDatabase.contributorDao().getById(id.value)?.revision ?: 0L) < echoed) {
                        // The echo carries the server's revision.
                    }
                }
                clientDatabase.contributorDao().getById(id.value)!!.imageFrame shouldBe framing

                // Another device replaces the photo: the server keeps no frame for it, and this device follows.
                serverContributorRepository.upsert(serverContributorRepository.findById(id.value)!!.copy(imagePath = "contributors/def.jpg"))
                withTimeout(ROUND_TRIP) {
                    while (
                        clientDatabase.contributorDao().getById(id.value)?.let { it.imagePath == "contributors/def.jpg" && it.imageFrame == null } != true
                    ) {
                        // The echo carries the cleared frame.
                    }
                }
            }
        }
    })
