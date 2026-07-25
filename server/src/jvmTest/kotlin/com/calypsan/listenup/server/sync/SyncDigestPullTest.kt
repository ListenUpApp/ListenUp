package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.server.testing.shouldFailWith
import com.calypsan.listenup.server.testing.shouldSucceed
import com.calypsan.listenup.server.testing.withTestApplication
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith

class SyncDigestPullTest :
    FunSpec({

        test("digest(tags) returns the domain digest") {
            withTestApplication {
                tagRepo.upsert(Tag("a", "alpha", "alpha", 0, 0))
                tagRepo.upsert(Tag("b", "beta", "beta", 0, 0))

                val d = syncService().digest("tags", cursor = 999).shouldSucceed()
                d.count shouldBe 2
                d.hash shouldStartWith "sha256:"
            }
        }

        test("digest on an unknown domain fails with SyncError.UnknownDomain") {
            withTestApplication {
                syncService().digest("nope", cursor = 0).shouldFailWith<SyncError.UnknownDomain>()
            }
        }
    })
