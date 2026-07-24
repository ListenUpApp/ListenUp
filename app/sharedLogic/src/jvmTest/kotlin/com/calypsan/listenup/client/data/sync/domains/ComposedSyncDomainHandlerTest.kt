package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.SyncDomainKey
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.sync.AccessFilteredSyncHandler
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private class RecordingApply : MirrorApply<Tag> {
    val upserts = mutableListOf<Tag>()
    val tombstonesById = mutableListOf<Triple<String, Long, Long>>()
    val tombstonesFromItem = mutableListOf<Tag>()

    override suspend fun upsert(payload: Tag) {
        upserts += payload
    }

    /** Wired into [DeleteSemantics.SoftDelete]; records the id-only tombstone args. */
    suspend fun tombstoneById(
        id: String,
        deletedAt: Long,
        revision: Long,
    ) {
        tombstonesById += Triple(id, deletedAt, revision)
    }

    override suspend fun tombstoneFromItem(item: Tag) {
        tombstonesFromItem += item
    }
}

private class PassThroughTransactionRunner : TransactionRunner {
    override suspend fun <R> atomically(block: suspend () -> R): R = block()
}

private fun tag(
    id: String = "t1",
    revision: Long = 5L,
) = Tag(id = id, name = "n", slug = "s", revision = revision, deletedAt = null, updatedAt = 100L)

private fun key() = SyncDomainKey("test_tags", Tag.serializer())

/** [ConflictPolicy.ServerWins] whose revision guard's local lookup always returns [local]. */
private fun serverWins(local: Long? = null) = ConflictPolicy.ServerWins<Tag>(RevisionGuard { local })

/** An [OutboxInFlightQuery] that reports every entity as having a queued local op. */
private val alwaysInFlight = OutboxInFlightQuery { _, _ -> true }

private fun domain(
    apply: RecordingApply,
    conflict: ConflictPolicy<Tag> = serverWins(),
    deletes: DeleteSemantics = DeleteSemantics.SoftDelete(apply::tombstoneById),
    digest: DigestParticipation = DigestParticipation.OptOut("test"),
    accessGate: AccessGate? = null,
) = MirroredDomain(
    key = key(),
    apply = apply,
    conflict = conflict,
    deletes = deletes,
    digest = digest,
    writes = WriteTier.OnlineOnly,
    accessGate = accessGate,
)

private fun updated(payload: Tag) =
    SyncEvent.Updated(
        id = payload.id,
        revision = payload.revision,
        occurredAt = 1L,
        payload = payload,
    )

class ComposedSyncDomainHandlerTest :
    FunSpec({
        val runner = PassThroughTransactionRunner()

        test("ServerWins applies Created and Updated payloads unconditionally") {
            val apply = RecordingApply()
            val handler = domain(apply).toHandler(runner, ClientSyncDomainRegistry())
            handler.onEvent(
                SyncEvent.Created(id = "t1", revision = 5L, occurredAt = 1L, payload = tag()),
            )
            handler.onEvent(updated(tag(revision = 6L)))
            apply.upserts.map { it.revision } shouldContainExactly listOf(5L, 6L)
        }

        test("NewerWins skips a stale snapshot and applies a fresher one") {
            val apply = RecordingApply()
            var localStamp: Long? = 200L
            val handler =
                domain(
                    apply,
                    conflict =
                        ConflictPolicy.NewerWins(
                            incomingStamp = { it.updatedAt },
                            existingStamp = { localStamp },
                        ),
                ).toHandler(runner, ClientSyncDomainRegistry())

            handler.onEvent(updated(tag())) // incoming 100 <= local 200
            apply.upserts.shouldBeEmpty()

            localStamp = 50L
            handler.onEvent(updated(tag())) // incoming 100 > local 50
            apply.upserts.map { it.id } shouldContainExactly listOf("t1")
        }

        test("NewerWins skips when stamps are equal — the >= boundary absorbs own-echoes") {
            val apply = RecordingApply()
            val handler =
                domain(
                    apply,
                    conflict =
                        ConflictPolicy.NewerWins(
                            incomingStamp = { it.updatedAt },
                            existingStamp = { 100L }, // equal to tag().updatedAt
                        ),
                ).toHandler(runner, ClientSyncDomainRegistry())
            handler.onEvent(updated(tag()))
            apply.upserts.shouldBeEmpty()
        }

        test("NewerWins applies when no local row exists (null stamp)") {
            val apply = RecordingApply()
            val handler =
                domain(
                    apply,
                    conflict =
                        ConflictPolicy.NewerWins(
                            incomingStamp = { it.updatedAt },
                            existingStamp = { null },
                        ),
                ).toHandler(runner, ClientSyncDomainRegistry())
            handler.onEvent(updated(tag()))
            apply.upserts.map { it.id } shouldContainExactly listOf("t1")
        }

        test("an inbound event is shielded while a local op for the entity is in flight") {
            val apply = RecordingApply()
            val handler =
                domain(apply).toHandler(runner, ClientSyncDomainRegistry(), inFlightOutbox = alwaysInFlight)
            handler.onEvent(updated(tag()))
            apply.upserts.shouldBeEmpty()
        }

        test("an inbound event applies when no local op is in flight") {
            val apply = RecordingApply()
            val handler =
                domain(apply).toHandler(runner, ClientSyncDomainRegistry(), inFlightOutbox = NoOutboxInFlight)
            handler.onEvent(updated(tag()))
            apply.upserts.map { it.id } shouldContainExactly listOf("t1")
        }

        test("the shield lifts once the op drains, and the next inbound event converges") {
            val apply = RecordingApply()
            var inFlight = true
            val handler =
                domain(apply).toHandler(
                    runner,
                    ClientSyncDomainRegistry(),
                    inFlightOutbox = OutboxInFlightQuery { _, _ -> inFlight },
                )
            handler.onEvent(updated(tag(revision = 5L))) // shielded
            apply.upserts.shouldBeEmpty()

            inFlight = false
            handler.onEvent(updated(tag(revision = 6L))) // op drained → applies
            apply.upserts.map { it.revision } shouldContainExactly listOf(6L)
        }

        test("a catch-up item is shielded while a local op for the entity is in flight") {
            val apply = RecordingApply()
            val handler =
                domain(apply).toHandler(runner, ClientSyncDomainRegistry(), inFlightOutbox = alwaysInFlight)
            handler.onCatchUpItem(tag(), isTombstone = false)
            apply.upserts.shouldBeEmpty()
        }

        test("EchoShielded never intercepts Deleted — a delete for an in-flight entity still tombstones") {
            val apply = RecordingApply()
            val handler =
                domain(apply).toHandler(runner, ClientSyncDomainRegistry(), inFlightOutbox = alwaysInFlight)
            handler.onEvent(SyncEvent.Deleted(id = "t1", revision = 7L, occurredAt = 99L))
            apply.tombstonesById shouldContainExactly listOf(Triple("t1", 99L, 7L))
        }

        test("SoftDelete routes SSE Deleted to tombstoneById") {
            val apply = RecordingApply()
            val handler = domain(apply).toHandler(runner, ClientSyncDomainRegistry())
            handler.onEvent(SyncEvent.Deleted(id = "t1", revision = 7L, occurredAt = 99L))
            apply.tombstonesById shouldContainExactly listOf(Triple("t1", 99L, 7L))
        }

        test("CatchUpOnly makes SSE Deleted a declared no-op") {
            val apply = RecordingApply()
            val handler =
                domain(apply, deletes = DeleteSemantics.CatchUpOnly("server id is not a local key"))
                    .toHandler(runner, ClientSyncDomainRegistry())
            handler.onEvent(SyncEvent.Deleted(id = "t1", revision = 7L, occurredAt = 99L))
            apply.tombstonesById.shouldBeEmpty()
        }

        test("catch-up tombstones always route to tombstoneFromItem") {
            val apply = RecordingApply()
            val handler = domain(apply).toHandler(runner, ClientSyncDomainRegistry())
            handler.onCatchUpItem(tag(), isTombstone = true)
            apply.tombstonesFromItem.map { it.id } shouldContainExactly listOf("t1")
        }

        test("catch-up items apply through the conflict policy when no op is in flight") {
            val apply = RecordingApply()
            val handler = domain(apply).toHandler(runner, ClientSyncDomainRegistry())
            handler.onCatchUpItem(tag(), isTombstone = false)
            apply.upserts.map { it.id } shouldContainExactly listOf("t1")
        }

        test("digest Full delegates to the declared rows lambda; OptOut returns null") {
            val apply = RecordingApply()
            val full =
                domain(apply, digest = DigestParticipation.Full { listOf("t1" to 5L) })
                    .toHandler(runner, ClientSyncDomainRegistry())
            full.localDigestRows(10L) shouldBe listOf("t1" to 5L)

            val optOut = domain(RecordingApply()).toHandler(runner, ClientSyncDomainRegistry())
            optOut.localDigestRows(10L).shouldBeNull()
        }

        test("handler self-registers under the key's wire name") {
            val registry = ClientSyncDomainRegistry()
            val handler = domain(RecordingApply()).toHandler(runner, registry)
            (registry.lookup("test_tags") === handler).shouldBeTrue()
        }

        test("an access gate produces an AccessFilteredSyncHandler; none produces a plain handler") {
            val gated =
                domain(
                    RecordingApply(),
                    accessGate =
                        AccessGate(
                            liveIds = { listOf("a") },
                            tombstoneByIds = { _, _ -> },
                            delta = AccessDeltaPolicy.LiveTailOnly("test gate: delta participation is irrelevant here"),
                        ),
                ).toHandler(runner, ClientSyncDomainRegistry())
            gated.shouldBeInstanceOf<AccessFilteredSyncHandler>()
            (gated as AccessFilteredSyncHandler).localLiveIds() shouldBe setOf("a")

            val plain = domain(RecordingApply()).toHandler(runner, ClientSyncDomainRegistry())
            (plain is AccessFilteredSyncHandler) shouldBe false
        }

        test("an access-gated handler still shields an in-flight entity's inbound event") {
            val apply = RecordingApply()
            val handler =
                domain(
                    apply,
                    accessGate =
                        AccessGate(
                            liveIds = { emptyList() },
                            tombstoneByIds = { _, _ -> },
                            delta = AccessDeltaPolicy.LiveTailOnly("test gate"),
                        ),
                ).toHandler(runner, ClientSyncDomainRegistry(), inFlightOutbox = alwaysInFlight)
            handler.onEvent(updated(tag()))
            apply.upserts.shouldBeEmpty()
        }

        test("revisionGuard skips a stale catch-up item (incoming < local)") {
            val apply = RecordingApply()
            val handler =
                domain(apply, conflict = serverWins(local = 10L))
                    .toHandler(runner, ClientSyncDomainRegistry())
            handler.onCatchUpItem(tag(revision = 5L), isTombstone = false)
            apply.upserts.shouldBeEmpty()
        }

        test("revisionGuard applies a catch-up item at an equal revision (digest-repair semantics)") {
            val apply = RecordingApply()
            val handler =
                domain(apply, conflict = serverWins(local = 5L))
                    .toHandler(runner, ClientSyncDomainRegistry())
            handler.onCatchUpItem(tag(revision = 5L), isTombstone = false)
            apply.upserts.map { it.id } shouldContainExactly listOf("t1")
        }

        test("revisionGuard applies a fresher catch-up item (incoming > local)") {
            val apply = RecordingApply()
            val handler =
                domain(apply, conflict = serverWins(local = 4L))
                    .toHandler(runner, ClientSyncDomainRegistry())
            handler.onCatchUpItem(tag(revision = 5L), isTombstone = false)
            apply.upserts.map { it.id } shouldContainExactly listOf("t1")
        }

        test("revisionGuard applies a catch-up item when the row has never been seen (null local revision)") {
            val apply = RecordingApply()
            val handler =
                domain(apply, conflict = serverWins(local = null))
                    .toHandler(runner, ClientSyncDomainRegistry())
            handler.onCatchUpItem(tag(revision = 5L), isTombstone = false)
            apply.upserts.map { it.id } shouldContainExactly listOf("t1")
        }

        test("revisionGuard skips a stale catch-up tombstone (incoming < local)") {
            val apply = RecordingApply()
            val handler =
                domain(apply, conflict = serverWins(local = 10L))
                    .toHandler(runner, ClientSyncDomainRegistry())
            handler.onCatchUpItem(tag(revision = 5L), isTombstone = true)
            apply.tombstonesFromItem.shouldBeEmpty()
        }

        test("revisionGuard applies a catch-up tombstone when the row has never been seen (null local revision)") {
            val apply = RecordingApply()
            val handler =
                domain(apply, conflict = serverWins(local = null))
                    .toHandler(runner, ClientSyncDomainRegistry())
            handler.onCatchUpItem(tag(revision = 5L), isTombstone = true)
            apply.tombstonesFromItem.map { it.id } shouldContainExactly listOf("t1")
        }

        test("revisionGuard skips a stale live Updated event (replayed-frame race)") {
            val apply = RecordingApply()
            val handler =
                domain(apply, conflict = serverWins(local = 10L))
                    .toHandler(runner, ClientSyncDomainRegistry())
            handler.onEvent(updated(tag(revision = 5L)))
            apply.upserts.shouldBeEmpty()
        }

        test("revisionGuard skips a stale live Deleted event") {
            val apply = RecordingApply()
            val handler =
                domain(apply, conflict = serverWins(local = 10L))
                    .toHandler(runner, ClientSyncDomainRegistry())
            handler.onEvent(SyncEvent.Deleted(id = "t1", revision = 5L, occurredAt = 99L))
            apply.tombstonesById.shouldBeEmpty()
        }

        test("the revision guard runs before the in-flight shield — a stale frame is skipped without consulting the outbox") {
            val apply = RecordingApply()
            var shieldConsulted = false
            val handler =
                domain(apply, conflict = serverWins(local = 10L))
                    .toHandler(
                        runner,
                        ClientSyncDomainRegistry(),
                        inFlightOutbox =
                            OutboxInFlightQuery { _, _ ->
                                shieldConsulted = true
                                true
                            },
                    )
            handler.onEvent(updated(tag(revision = 5L)))
            shieldConsulted.shouldBeFalse()
            apply.upserts.shouldBeEmpty()
        }

        test("a policy with no revision guard (AppendOnly) applies a would-be-stale event unconditionally") {
            val apply = RecordingApply()
            val handler =
                domain(apply, conflict = ConflictPolicy.AppendOnly())
                    .toHandler(runner, ClientSyncDomainRegistry())
            handler.onEvent(updated(tag(revision = 5L)))
            apply.upserts.map { it.revision } shouldContainExactly listOf(5L)
        }
    })
