package com.calypsan.listenup.client.data.local.db

import androidx.sqlite.SQLiteConnection

/**
 * Executes a single DDL statement that returns no rows.
 *
 * A seam over `androidx.sqlite`'s `execSQL`, which is **not callable from common code** once a
 * web target exists. The library splits its core API by source set rather than declaring it in
 * the common `expect interface`:
 *
 * | | nonWeb | web |
 * |---|---|---|
 * | `SQLiteConnection.prepare` | `fun` | `suspend fun` |
 * | `SQLiteStatement.step` | `fun` | `suspend fun` |
 * | `execSQL` | `fun` | `suspend fun` |
 *
 * That split is deliberate: a browser runs SQLite as wasm inside a Web Worker, so every call
 * crosses a `postMessage` boundary and has to be asynchronous, while native platforms would pay
 * `suspend` for nothing. The cost is that `prepare`/`step`/`execSQL` are absent from the
 * all-targets intersection, so `compileCommonMainKotlinMetadata` cannot resolve them.
 *
 * The signature here is `suspend` because that is the shape web forces and the one every caller
 * can satisfy — [FtsTableCallback.onOpen] is already a suspend function. Each `actual` delegates
 * straight to `execSQL`; the bodies are textually identical and differ only in which overload
 * they resolve to.
 */
internal expect suspend fun SQLiteConnection.executeDdl(sql: String)
