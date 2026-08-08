package com.calypsan.listenup.client.data.local.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

// Resolves to the non-suspend `execSQL` from androidx.sqlite's nonWebMain source set —
// Android's SQLite is in-process, so the call needs no suspension.
internal actual suspend fun SQLiteConnection.executeDdl(sql: String) = execSQL(sql)
