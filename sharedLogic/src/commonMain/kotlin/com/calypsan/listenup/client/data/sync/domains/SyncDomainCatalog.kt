package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.client.data.local.db.BookEntityMapper
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.local.documents.DocumentStorage
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.AvatarDownloadRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage

/**
 * The explicit list of every declared sync domain — the client's complete sync
 * rulebook in one value. [mirrored] domains are Room-mirrored; [refreshed] domains
 * are refresh-driven. The server's registrations are asserted 1:1 against [mirrored]
 * by the completeness spec; the four [refreshed] triggers are asserted there too.
 */
internal class SyncDomainCatalog(
    val mirrored: List<MirroredDomain<*>>,
    val refreshed: List<RefreshedDomain>,
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
    authSession: AuthSession,
    avatarDownloadRepository: AvatarDownloadRepository,
    pingPresence: () -> Unit,
    pingCampfires: () -> Unit,
    refetchServerInfo: suspend () -> Unit,
    refetchPreferences: suspend () -> Unit,
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
                readingOrdersDomain(database = database),
                readingOrderBooksDomain(database = database),
                readingOrderFollowsDomain(database = database),
                playbackPositionsDomain(database = database),
                listeningEventsDomain(database = database, authSession = authSession),
                activitiesDomain(database = database),
                userStatsDomain(database = database),
                publicProfilesDomain(
                    database = database,
                    avatarDownloadRepository = avatarDownloadRepository,
                ),
                seriesDomain(database = database),
                collectionsDomain(database = database),
                collectionBooksDomain(database = database),
                collectionSharesDomain(database = database),
                contributorsDomain(database = database, imageStorage = imageStorage),
                booksDomain(
                    database = database,
                    mapper = mapper,
                    imageStorage = imageStorage,
                    documentStorage = documentStorage,
                ),
                adminUserRosterDomain(database = database),
            ),
        refreshed =
            listOf(
                presenceDomain(ping = pingPresence),
                campfireDomain(ping = pingCampfires),
                serverInfoDomain(refetch = refetchServerInfo),
                preferencesDomain(refetch = refetchPreferences),
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
