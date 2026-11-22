package com.calypsan.listenup.server.testing

import com.calypsan.listenup.api.sync.GenreSyncPayload
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry

// Reads a test takes straight from the database to check what a write stored. Clients read these
// facts from their local mirror, so the server offers no RPC for them.

/** The live genre [id] as stored, or null when it is missing or tombstoned. */
internal suspend fun ListenUpDatabase.liveGenre(id: GenreId): GenreSyncPayload? =
    GenreRepository(this, ChangeBus(), SyncRegistry()).findById(id.value)?.takeIf { it.deletedAt == null }
