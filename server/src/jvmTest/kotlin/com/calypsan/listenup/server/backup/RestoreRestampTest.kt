package com.calypsan.listenup.server.backup

import com.calypsan.listenup.api.dto.backup.BackupEvent
import com.calypsan.listenup.api.dto.backup.RestoreResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.core.BackupId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.TagRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest

/**
 * A restore swaps in rows numbered before the backup, while every client already holds revisions up to
 * the pre-restore counter. A client refuses a row older than the one it holds, so the restored rows
 * must come back numbered above everything a client has seen, in their original order.
 */
class RestoreRestampTest :
    FunSpec({

        suspend fun restore(
            fixture: BackupTestFixture,
            id: String,
        ) = RestoreOrchestrator(
            paths = fixture.paths,
            archive = fixture.archive,
            dbHandle = fixture.handle,
            maintenance = MaintenanceState(),
            eventBus = MutableSharedFlow<BackupEvent>(extraBufferCapacity = 64),
            changeBus = ChangeBus(),
        ).restore(BackupId(id))

        fun BackupTestFixture.counter(): Int = handle.queryScalarInt("SELECT value FROM sync_meta WHERE key = 'revision_counter'")

        fun tag(
            id: String,
            name: String,
        ) = Tag(id = id, name = name, slug = id, revision = 0, updatedAt = 0)

        test("restored rows come back above every revision clients have seen, in their original order") {
            runTest {
                backupTestFixture().use { fixture ->
                    val tags = TagRepository(ListenUpDatabase(fixture.handle.sqlDriver), ChangeBus(), SyncRegistry())
                    tags.upsert(tag("tag-a", "Fantasy"))
                    tags.upsert(tag("tag-b", "Horror"))
                    fixture.archive.create("before-edit", includeImages = false, onEvent = {})

                    // After the backup: tag-a is renamed and tag-c created, so clients have seen these revisions.
                    tags.updateName("tag-a", "Epic Fantasy")
                    tags.upsert(tag("tag-c", "Mystery"))
                    val seenByClients = fixture.counter().toLong()

                    restore(fixture, "before-edit").shouldBeInstanceOf<AppResult.Success<RestoreResult>>()

                    val pulled = tags.pullSince(userId = null, cursor = 0, limit = 100).items
                    pulled.map { it.id to it.name } shouldContainExactly listOf("tag-a" to "Fantasy", "tag-b" to "Horror")
                    val (restoredA, restoredB) = pulled
                    restoredA.revision shouldBeGreaterThan seenByClients
                    restoredB.revision shouldBeGreaterThan restoredA.revision

                    // A client that saw everything before the restore still pulls the restored rows incrementally.
                    tags.pullSince(userId = null, cursor = seenByClients, limit = 100).items.map { it.id } shouldContainExactly
                        listOf("tag-a", "tag-b")
                }
            }
        }

        test("the counter ends past the last re-stamped row, so the next write sorts after it") {
            runTest {
                backupTestFixture().use { fixture ->
                    val tags = TagRepository(ListenUpDatabase(fixture.handle.sqlDriver), ChangeBus(), SyncRegistry())
                    tags.upsert(tag("tag-a", "Fantasy"))
                    fixture.archive.create("counter", includeImages = false, onEvent = {})
                    fixture.handle.execSql("UPDATE sync_meta SET value = 500 WHERE key = 'revision_counter'")

                    restore(fixture, "counter").shouldBeInstanceOf<AppResult.Success<RestoreResult>>()

                    val restamped = tags.findById("tag-a").shouldNotBeNull().revision
                    fixture.counter().toLong() shouldBe restamped
                    val next = tags.upsert(tag("tag-n", "New")).shouldBeInstanceOf<AppResult.Success<Tag>>().data
                    next.revision shouldBeGreaterThan restamped
                }
            }
        }

        test("a match receipt moves with its book, so Undo still offers itself after a restore") {
            runTest {
                backupTestFixture().use { fixture ->
                    val now = 1L
                    fixture.handle.execSql(
                        "INSERT INTO libraries(id, name, created_at, updated_at, revision) VALUES ('lib', 'Lib', $now, $now, 1)",
                        "INSERT INTO books(id, library_id, title, total_duration, root_rel_path, scanned_at, revision, " +
                            "created_at, updated_at) VALUES ('book-1', 'lib', 'Book', 0, 'book-1', $now, 7, $now, $now)",
                        "INSERT INTO match_receipts(id, entity_kind, entity_id, applied_by, applied_at, revision_after, " +
                            "snapshot, changes) VALUES ('receipt-1', 'book', 'book-1', 'u1', $now, 7, '{}', '[]')",
                        "UPDATE sync_meta SET value = 7 WHERE key = 'revision_counter'",
                    )
                    fixture.archive.create("receipts", includeImages = false, onEvent = {})
                    fixture.handle.execSql("UPDATE sync_meta SET value = 900 WHERE key = 'revision_counter'")

                    restore(fixture, "receipts").shouldBeInstanceOf<AppResult.Success<RestoreResult>>()

                    val bookRevision = fixture.handle.queryScalarInt("SELECT revision FROM books WHERE id = 'book-1'")
                    bookRevision shouldBeGreaterThan 900
                    fixture.handle.queryScalarInt("SELECT revision_after FROM match_receipts WHERE id = 'receipt-1'") shouldBe
                        bookRevision
                }
            }
        }
    })
