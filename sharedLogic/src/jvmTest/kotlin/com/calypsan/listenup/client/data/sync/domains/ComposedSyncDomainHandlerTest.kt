package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.SyncDomainKey
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.sync.AccessFilteredSyncHandler
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import io.kotest.core.spec.style.FunSpec
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

    override suspend fun tombstoneById(
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

private fun domain(
    apply: MirrorApply<Tag>,
    conflict: ConflictPolicy<Tag> = ConflictPolicy.ServerWins(),
    deletes: DeleteSemantics = DeleteSemantics.SoftDelete,
    digest: DigestParticipation = DigestParticipation.OptOut("test"),
    accessGate: AccessGate? = null,
    revisionGuard: RevisionGuard<Tag>? = null,
) = MirroredDomain(
    key = key(),
    syncIdOf = { it.id },
    apply = apply,
    conflict = conflict,
    deletes = deletes,
    digest = digest,
    writes = WriteTier.OnlineOnly,
    accessGate = accessGate,
    revisionGuard = revisionGuard,
)

private fun updated(payload: Tag) =
    SyncEvent.Updated(
        id = payload.id,
        revision = payload.revision,
        occurredAt = 1L,
        payload = payload,
    )

/** A [RevisionGuard] whose local lookup always returns the fixed [local] value. */
private fun guard(local: Long?) =
    RevisionGuard<Tag>(
        incomingRevision = { it.revision },
        localRevision = { local },
    )

class ComposedSyncDomainHandlerTest :
    FunSpec({
        val runner = PassThroughTransactionRunner()

        test("ServerWins applies Created and Updated payloads unconditionally") {
            val apply = RecordingApply()
            val handler = domain(apply).toHandler(runner, ClientSyncDomainRegistry())
            handler.onEvent(
                SyncEvent.Created(id = "t1", revision = 5L, occurredAt = 1L, payload = tag()),
                isOwnEcho = false,
            )
            handler.onEvent(updated(tag(revision = 6L)), isOwnEcho = false)
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

            handler.onEvent(updated(tag()), isOwnEcho = false) // incoming 100 <= local 200
            apply.upserts.shouldBeEmpty()

            localStamp = 50L
            handler.onEvent(updated(tag()), isOwnEcho = false) // incoming 100 > local 50
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
            handler.onEvent(updated(tag()), isOwnEcho = false)
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
            handler.onEvent(updated(tag()), isOwnEcho = false)
            apply.upserts.map { it.id } shouldContainExactly listOf("t1")
        }

        test("EchoShielded consumes an own echo when the shield handles it") {
            val apply = RecordingApply()
            val shielded = mutableListOf<String>()
            val handler =
                domain(
                    apply,
                    conflict =
                        ConflictPolicy.EchoShielded { id, _ ->
                            shielded += id
                            true
                        },
                ).toHandler(runner, ClientSyncDomainRegistry())

            handler.onEvent(updated(tag()), isOwnEcho = true)
            shielded shouldContainExactly listOf("t1")
            apply.upserts.shouldBeEmpty()

            handler.onEvent(updated(tag()), isOwnEcho = false) // remote write applies fully
            apply.upserts.map { it.id } shouldContainExactly listOf("t1")
        }

        test("EchoShielded falls through to a full apply when the shield declines") {
            val apply = RecordingApply()
            val handler =
                domain(apply, conflict = ConflictPolicy.EchoShielded { _, _ -> false })
                    .toHandler(runner, ClientSyncDomainRegistry())
            handler.onEvent(updated(tag()), isOwnEcho = true)
            apply.upserts.map { it.id } shouldContainExactly listOf("t1")
        }

        test("EchoShielded never intercepts Deleted — an own-echo delete still tombstones") {
            val apply = RecordingApply()
            val handler =
                domain(apply, conflict = ConflictPolicy.EchoShielded { _, _ -> true })
                    .toHandler(runner, ClientSyncDomainRegistry())
            handler.onEvent(SyncEvent.Deleted(id = "t1", revision = 7L, occurredAt = 99L), isOwnEcho = true)
            apply.tombstonesById shouldContainExactly listOf(Triple("t1", 99L, 7L))
        }

        test("EchoShielded intercepts Created echoes like Updated echoes") {
            val apply = RecordingApply()
            val shielded = mutableListOf<String>()
            val handler =
                domain(
                    apply,
                    conflict =
                        ConflictPolicy.EchoShielded { id, _ ->
                            shielded += id
                            true
                        },
                ).toHandler(runner, ClientSyncDomainRegistry())
            handler.onEvent(
                SyncEvent.Created(id = "t1", revision = 5L, occurredAt = 1L, payload = tag()),
                isOwnEcho = true,
            )
            shielded shouldContainExactly listOf("t1")
            apply.upserts.shouldBeEmpty()
        }

        test("SoftDelete routes SSE Deleted to tombstoneById") {
            val apply = RecordingApply()
            val handler = domain(apply).toHandler(runner, ClientSyncDomainRegistry())
            handler.onEvent(SyncEvent.Deleted(id = "t1", revision = 7L, occurredAt = 99L), isOwnEcho = false)
            apply.tombstonesById shouldContainExactly listOf(Triple("t1", 99L, 7L))
        }

        test("CatchUpOnly makes SSE Deleted a declared no-op") {
            val apply = RecordingApply()
            val handler =
                domain(apply, deletes = DeleteSemantics.CatchUpOnly("server id is not a local key"))
                    .toHandler(runner, ClientSyncDomainRegistry())
            handler.onEvent(SyncEvent.Deleted(id = "t1", revision = 7L, occurredAt = 99L), isOwnEcho = false)
            apply.tombstonesById.shouldBeEmpty()
        }

        test("catch-up tombstones always route to tombstoneFromItem") {
            val apply = RecordingApply()
            val handler = domain(apply).toHandler(runner, ClientSyncDomainRegistry())
            handler.onCatchUpItem(tag(), isTombstone = true)
            apply.tombstonesFromItem.map { it.id } shouldContainExactly listOf("t1")
        }

        test("catch-up items apply through the conflict policy with no echo") {
            val apply = RecordingApply()
            val handler =
                domain(apply, conflict = ConflictPolicy.EchoShielded { _, _ -> true })
                    .toHandler(runner, ClientSyncDomainRegistry())
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
                    accessGate = AccessGate(localLiveIds = { setOf("a") }, pruneTo = { _, _ -> }),
                ).toHandler(runner, ClientSyncDomainRegistry())
            gated.shouldBeInstanceOf<AccessFilteredSyncHandler>()
            (gated as AccessFilteredSyncHandler).localLiveIds() shouldBe setOf("a")

            val plain = domain(RecordingApply()).toHandler(runner, ClientSyncDomainRegistry())
            (plain is AccessFilteredSyncHandler) shouldBe false
        }

        test("revisionGuard skips a stale catch-up item (incoming < local)") {
            val apply = RecordingApply()
            val handler =
                domain(apply, revisionGuard = guard(local = 10L))
                    .toHandler(runner, ClientSyncDomainRegistry())
            handler.onCatchUpItem(tag(revision = 5L), isTombstone = false)
            apply.upserts.shouldBeEmpty()
        }

        test("revisionGuard applies a catch-up item at an equal revision (digest-repair semantics)") {
            val apply = RecordingApply()
            val handler =
                domain(apply, revisionGuard = guard(local = 5L))
                    .toHandler(runner, ClientSyncDomainRegistry())
            handler.onCatchUpItem(tag(revision = 5L), isTombstone = false)
            apply.upserts.map { it.id } shouldContainExactly listOf("t1")
        }

        test("revisionGuard applies a fresher catch-up item (incoming > local)") {
            val apply = RecordingApply()
            val handler =
                domain(apply, revisionGuard = guard(local = 4L))
                    .toHandler(runner, ClientSyncDomainRegistry())
            handler.onCatchUpItem(tag(revision = 5L), isTombstone = false)
            apply.upserts.map { it.id } shouldContainExactly listOf("t1")
        }

        test("revisionGuard applies a catch-up item when the row has never been seen (null local revision)") {
            val apply = RecordingApply()
            val handler =
                domain(apply, revisionGuard = guard(local = null))
                    .toHandler(runner, ClientSyncDomainRegistry())
            handler.onCatchUpItem(tag(revision = 5L), isTombstone = false)
            apply.upserts.map { it.id } shouldContainExactly listOf("t1")
        }

        test("revisionGuard skips a stale catch-up tombstone (incoming < local)") {
            val apply = RecordingApply()
            val handler =
                domain(apply, revisionGuard = guard(local = 10L))
                    .toHandler(runner, ClientSyncDomainRegistry())
            handler.onCatchUpItem(tag(revision = 5L), isTombstone = true)
            apply.tombstonesFromItem.shouldBeEmpty()
        }

        test("revisionGuard applies a catch-up tombstone when the row has never been seen (null local revision)") {
            val apply = RecordingApply()
            val handler =
                domain(apply, revisionGuard = guard(local = null))
                    .toHandler(runner, ClientSyncDomainRegistry())
            handler.onCatchUpItem(tag(revision = 5L), isTombstone = true)
            apply.tombstonesFromItem.map { it.id } shouldContainExactly listOf("t1")
        }

        test("revisionGuard skips a stale live Updated event (replayed-frame race)") {
            val apply = RecordingApply()
            val handler =
                domain(apply, revisionGuard = guard(local = 10L))
                    .toHandler(runner, ClientSyncDomainRegistry())
            handler.onEvent(updated(tag(revision = 5L)), isOwnEcho = false)
            apply.upserts.shouldBeEmpty()
        }

        test("revisionGuard skips a stale live Deleted event") {
            val apply = RecordingApply()
            val handler =
                domain(apply, revisionGuard = guard(local = 10L))
                    .toHandler(runner, ClientSyncDomainRegistry())
            handler.onEvent(SyncEvent.Deleted(id = "t1", revision = 5L, occurredAt = 99L), isOwnEcho = false)
            apply.tombstonesById.shouldBeEmpty()
        }

        test("revisionGuard runs before the EchoShielded shield — a stale own echo does not invoke it") {
            val apply = RecordingApply()
            val shielded = mutableListOf<String>()
            val handler =
                domain(
                    apply,
                    conflict =
                        ConflictPolicy.EchoShielded { id, _ ->
                            shielded += id
                            true
                        },
                    revisionGuard = guard(local = 10L),
                ).toHandler(runner, ClientSyncDomainRegistry())
            handler.onEvent(updated(tag(revision = 5L)), isOwnEcho = true)
            shielded.shouldBeEmpty()
            apply.upserts.shouldBeEmpty()
        }

        test("a domain with no revisionGuard applies a would-be-stale event unconditionally (guard opt-out)") {
            val apply = RecordingApply()
            val handler = domain(apply).toHandler(runner, ClientSyncDomainRegistry())
            handler.onEvent(updated(tag(revision = 5L)), isOwnEcho = false)
            apply.upserts.map { it.revision } shouldContainExactly listOf(5L)
        }
    })
