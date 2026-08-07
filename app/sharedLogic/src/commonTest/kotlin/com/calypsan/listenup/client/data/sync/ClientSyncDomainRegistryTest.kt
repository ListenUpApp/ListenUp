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

        test("playback_positions catches up first, ahead of alphabetically-earlier decoration") {
            val registry = ClientSyncDomainRegistry()
            // Registration order deliberately does not favour playback_positions.
            listOf("activities", "admin_user_roster", "book_moods", "book_tags", "playback_positions", "tags")
                .forEach { registry.register(handler(it)) }

            // Catch-up is strictly sequential and each domain costs a full round-trip, so position
            // in this list is latency. playback_positions decides WHERE A BOOK RESUMES; plain
            // alphabetical order queued it behind four domains of decoration.
            registry.registeredDomains().first() shouldBe "playback_positions"
        }

        test("domains with no declared priority stay alphabetical after the prioritised ones") {
            val registry = ClientSyncDomainRegistry()
            listOf("zeta", "alpha", "playback_positions", "mu").forEach { registry.register(handler(it)) }

            registry.registeredDomains() shouldBe listOf("playback_positions", "alpha", "mu", "zeta")
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
