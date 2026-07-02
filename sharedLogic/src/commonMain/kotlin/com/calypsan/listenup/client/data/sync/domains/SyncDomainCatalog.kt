package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.client.data.local.db.BookEntityMapper
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.local.documents.DocumentStorage
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.domain.repository.ImageStorage

/**
 * The explicit list of every declared sync domain — the client's complete
 * sync rulebook in one value. Grows as Phase 2 migrates the remaining hand-written
 * handlers; when the migration completes, this list and the server's registrations
 * are asserted 1:1 by the completeness spec.
 */
internal class SyncDomainCatalog(
    val mirrored: List<MirroredDomain<*>>,
)

/**
 * The one place the catalog's contents are listed. Every migration (Plan 2a–2c) adds
 * exactly one line here; DI and the catalog spec both build from this factory so the
 * two can never drift. Parameters are the union of dependencies the descriptors need.
 */
internal fun syncDomainCatalog(
    database: ListenUpDatabase,
    mapper: BookEntityMapper,
    imageStorage: ImageStorage,
    documentStorage: DocumentStorage? = null,
): SyncDomainCatalog =
    SyncDomainCatalog(
        mirrored =
            listOf(
                tagsDomain(database = database),
                genresDomain(database = database),
                moodsDomain(database = database),
                bookTagsDomain(database = database),
                bookMoodsDomain(database = database),
                librariesDomain(database = database),
                libraryFoldersDomain(database = database),
                shelvesDomain(database = database),
                shelfBooksDomain(database = database),
                playbackPositionsDomain(database = database),
                seriesDomain(database = database),
                collectionsDomain(database = database),
                contributorsDomain(database = database, imageStorage = imageStorage),
                booksDomain(
                    database = database,
                    mapper = mapper,
                    imageStorage = imageStorage,
                    documentStorage = documentStorage,
                ),
            ),
    )

/**
 * Compiles every catalog entry into its runtime handler at app start — the
 * registry-population step that replaces per-handler `createdAtStart` singles.
 */
internal class ComposedHandlerRegistrar(
    private val catalog: SyncDomainCatalog,
    private val transactionRunner: TransactionRunner,
    private val registry: ClientSyncDomainRegistry,
) {
    /** Construct (and thereby self-register) a handler for every declared domain. */
    fun registerAll() {
        catalog.mirrored.forEach { it.toHandler(transactionRunner, registry) }
    }
}
