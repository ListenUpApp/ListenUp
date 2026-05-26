package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.core.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class TagSyncDomainHandlerTest :
    FunSpec({

        test("self-registers under domainName 'tags' on construction") {
            val registry = ClientSyncDomainRegistry()
            val handler = TagSyncDomainHandler(registry = registry)
            registry.lookup("tags") shouldBe handler
        }

        test("onEvent always returns Success") {
            runTest {
                val handler = TagSyncDomainHandler(registry = ClientSyncDomainRegistry())
                val firstRev = 1L
                val firstUpdated = 1L
                val event =
                    SyncEvent.Created(
                        id = "t1",
                        revision = firstRev,
                        occurredAt = firstUpdated,
                        clientOpId = null,
                        payload = Tag(id = "t1", name = "alpha", slug = "alpha", revision = firstRev, updatedAt = firstUpdated),
                    )
                handler.onEvent(event, isOwnEcho = false).shouldBeInstanceOf<AppResult.Success<Unit>>()
            }
        }

        test("onCatchUpItem always returns Success") {
            runTest {
                val handler = TagSyncDomainHandler(registry = ClientSyncDomainRegistry())
                val rev = 1L
                val updated = 1L
                val tag = Tag(id = "t1", name = "alpha", slug = "alpha", revision = rev, updatedAt = updated)
                handler.onCatchUpItem(tag, isTombstone = false).shouldBeInstanceOf<AppResult.Success<Unit>>()
            }
        }
    })
