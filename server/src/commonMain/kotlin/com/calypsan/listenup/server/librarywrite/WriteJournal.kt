package com.calypsan.listenup.server.librarywrite

import com.calypsan.listenup.server.io.deleteRecursively
import com.calypsan.listenup.server.io.fileIoDispatcher
import com.calypsan.listenup.server.io.readBytes
import com.calypsan.listenup.server.io.readText
import com.calypsan.listenup.server.io.writeBytes
import com.calypsan.listenup.server.io.writeText
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Crash-resumable journal for in-flight [WriteManifest]s, filesystem-truth like
 * [com.calypsan.listenup.server.absimport.ImportPaths] / `ImportStore` — no database table.
 *
 * One `<opId>.json` file per manifest under [journalDir] holds the ops plus a per-op `done` flag,
 * index-aligned with the manifest's op list. [WriteOp.WriteFile] payloads are staged to a sibling
 * `<opId>.data/<index>` file rather than inlined in the JSON — write bytes are typically
 * image/document-sized and don't belong in a struct rewritten on every op completion. Both the
 * `<opId>.json` file and its `.data/` sibling are removed once every op reports done ([delete]).
 *
 * [LibraryWriteBroker] is the only caller: [persist] before executing a manifest's first op,
 * [markOpDone] after each op lands, [delete] once the whole manifest completes, and [listPending]
 * to reconstruct interrupted manifests for [LibraryWriteBroker.recoverJournal] at boot.
 */
class WriteJournal(
    private val journalDir: Path,
) {
    /** Persists [manifest] with every op undone, staging each [WriteOp.WriteFile]'s bytes to a data file. */
    suspend fun persist(manifest: WriteManifest): Unit =
        onIo {
            SystemFileSystem.createDirectories(journalDir)
            var dataIndex = 0
            val persistedOps =
                manifest.ops.map { op ->
                    when (op) {
                        is WriteOp.EnsureDir -> PersistedOp.PersistedEnsureDir(dir = op.dir.toString())
                        is WriteOp.MoveFile -> PersistedOp.PersistedMoveFile(from = op.from.toString(), to = op.to.toString())
                        is WriteOp.WriteFile -> {
                            val index = dataIndex++
                            SystemFileSystem.createDirectories(dataDirFor(manifest.opId))
                            dataFileFor(manifest.opId, index).writeBytes(op.bytes)
                            PersistedOp.PersistedWriteFile(target = op.target.toString(), dataIndex = index)
                        }
                        is WriteOp.DeleteFile -> PersistedOp.PersistedDeleteFile(target = op.target.toString())
                    }
                }
            jsonFor(manifest.opId).writeText(json.encodeToString(PersistedManifest(manifest.opId, persistedOps)))
        }

    /** Marks op [index] of manifest [opId] as done, so a later [listPending] skips re-applying it. */
    suspend fun markOpDone(
        opId: String,
        index: Int,
    ): Unit =
        onIo {
            val file = jsonFor(opId)
            val current = json.decodeFromString<PersistedManifest>(file.readText())
            val updated = current.copy(ops = current.ops.mapIndexed { i, op -> if (i == index) op.markDone() else op })
            file.writeText(json.encodeToString(updated))
        }

    /** Removes the journal entry and any staged data for [opId] — call once every op has completed. */
    suspend fun delete(opId: String): Unit =
        onIo {
            SystemFileSystem.delete(jsonFor(opId), mustExist = false)
            deleteRecursively(dataDirFor(opId))
        }

    /** Every manifest still in the journal, with staged [WriteOp.WriteFile] bytes reattached, and its per-op done flags. */
    suspend fun listPending(): List<PendingManifest> =
        onIo {
            if (!SystemFileSystem.exists(journalDir)) return@onIo emptyList()
            SystemFileSystem
                .list(journalDir)
                .filter { it.name.endsWith(".json") }
                .map { file ->
                    val persisted = json.decodeFromString<PersistedManifest>(file.readText())
                    PendingManifest(
                        manifest =
                            WriteManifest(
                                opId = persisted.opId,
                                ops = persisted.ops.map { it.toWriteOp(persisted.opId) },
                            ),
                        doneFlags = persisted.ops.map { it.done },
                    )
                }
        }

    private fun jsonFor(opId: String) = Path(journalDir, "$opId.json")

    private fun dataDirFor(opId: String) = Path(journalDir, "$opId.data")

    private fun dataFileFor(
        opId: String,
        index: Int,
    ) = Path(dataDirFor(opId), index.toString())

    private fun PersistedOp.toWriteOp(opId: String): WriteOp =
        when (this) {
            is PersistedOp.PersistedEnsureDir -> WriteOp.EnsureDir(Path(dir))
            is PersistedOp.PersistedMoveFile -> WriteOp.MoveFile(Path(from), Path(to))
            is PersistedOp.PersistedWriteFile -> WriteOp.WriteFile(Path(target), dataFileFor(opId, dataIndex).readBytes())
            is PersistedOp.PersistedDeleteFile -> WriteOp.DeleteFile(Path(target))
        }

    private suspend fun <T> onIo(block: () -> T): T = withContext(fileIoDispatcher) { block() }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** A manifest reconstructed from the journal, paired with which of its ops are already done. */
data class PendingManifest(
    val manifest: WriteManifest,
    val doneFlags: List<Boolean>,
)

/** Server-internal persisted shape of a [WriteManifest] (`<opId>.json`). */
@Serializable
private data class PersistedManifest(
    @SerialName("opId")
    val opId: String,
    @SerialName("ops")
    val ops: List<PersistedOp>,
)

/** Server-internal persisted shape of a single [WriteOp], plus its completion flag. */
@Serializable
private sealed interface PersistedOp {
    val done: Boolean

    @Serializable
    @SerialName("PersistedOp.EnsureDir")
    data class PersistedEnsureDir(
        @SerialName("dir")
        val dir: String,
        @SerialName("done")
        override val done: Boolean = false,
    ) : PersistedOp

    @Serializable
    @SerialName("PersistedOp.MoveFile")
    data class PersistedMoveFile(
        @SerialName("from")
        val from: String,
        @SerialName("to")
        val to: String,
        @SerialName("done")
        override val done: Boolean = false,
    ) : PersistedOp

    @Serializable
    @SerialName("PersistedOp.WriteFile")
    data class PersistedWriteFile(
        @SerialName("target")
        val target: String,
        @SerialName("dataIndex")
        val dataIndex: Int,
        @SerialName("done")
        override val done: Boolean = false,
    ) : PersistedOp

    @Serializable
    @SerialName("PersistedOp.DeleteFile")
    data class PersistedDeleteFile(
        @SerialName("target")
        val target: String,
        @SerialName("done")
        override val done: Boolean = false,
    ) : PersistedOp
}

private fun PersistedOp.markDone(): PersistedOp =
    when (this) {
        is PersistedOp.PersistedEnsureDir -> copy(done = true)
        is PersistedOp.PersistedMoveFile -> copy(done = true)
        is PersistedOp.PersistedWriteFile -> copy(done = true)
        is PersistedOp.PersistedDeleteFile -> copy(done = true)
    }
