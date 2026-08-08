package com.calypsan.listenup.client.data.local.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

// Resolves to the SUSPEND `execSQL` from androidx.sqlite's webMain source set — the browser runs
// SQLite as wasm in a Web Worker, so the call crosses a postMessage boundary. Same body as the
// other actuals; only the resolved overload differs, which is the whole reason this seam exists.
internal actual suspend fun SQLiteConnection.executeDdl(sql: String) = execSQL(sql)
