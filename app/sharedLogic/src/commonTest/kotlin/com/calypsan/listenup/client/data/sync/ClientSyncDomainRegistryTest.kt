package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.api.result.AppResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

class ClientSyncDomainRegistryTest :
    FunSpec({

        fun handler(name: String): SyncDomainHandler<Tag> =
            object : SyncDomainHandler<Tag> {
                override val domainName = name
                override val payloadSerializer = Tag.serializer()

                override fun syncId(item: Tag): String = item.id

                override suspend fun onEvent(
                    event: SyncEvent<Tag>,
                ): AppResult<Unit> = AppResult.Success(Unit)

                override suspend fun onCatchUpItem(
                    item: Tag,
                    isTombstone: Boolean,
                ): AppResult<Unit> = AppResult.Success(Unit)

                override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> = emptyList()
            }

        test("register + lookup round-trips") {
            val registry = ClientSyncDomainRegistry()
            val h = handler("tags")
            registry.register(h)
            registry.lookup("tags") shouldBe h
        }

        test("lookup returns null for unknown domain") {
            val registry = ClientSyncDomainRegistry()
            registry.lookup("nope") shouldBe null
        }

        test("registeredDomains returns sorted names") {
            val registry = ClientSyncDomainRegistry()
            registry.register(handler("zeta"))
            registry.register(handler("alpha"))
            registry.register(handler("mu"))
            registry.registeredDomains() shouldContainExactlyInAnyOrder listOf("alpha", "mu", "zeta")
        }

        test("re-registering the same instance for the same domain is idempotent") {
            val registry = ClientSyncDomainRegistry()
            val h = handler("tags")
            registry.register(h)
            registry.register(h) // should not throw
            registry.lookup("tags") shouldBe h
        }

        test("registering a different handler for an existing domain throws (programmer error)") {
            val registry = ClientSyncDomainRegistry()
            registry.register(handler("tags"))
            shouldThrow<IllegalStateException> { registry.register(handler("tags")) }
        }
    })
