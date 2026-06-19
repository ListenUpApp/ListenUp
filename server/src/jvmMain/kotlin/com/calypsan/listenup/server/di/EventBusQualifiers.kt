package com.calypsan.listenup.server.di

import org.koin.core.qualifier.StringQualifier
import org.koin.core.qualifier.named

/**
 * Koin qualifiers for the process-wide progress event buses.
 *
 * Every bus is a `MutableSharedFlow<…>`, and Koin keys definitions on the **erased** `KClass`
 * (`MutableSharedFlow::class`) — so `MutableSharedFlow<ScanEvent>`, `MutableSharedFlow<ImportEvent>`,
 * `MutableSharedFlow<BackupEvent>`, and `MutableSharedFlow<ScanResult>` would all collide on a single
 * binding. Without distinct qualifiers the last-registered module wins and the buses collapse onto one
 * shared instance, intermingling event types until a consumer's `observeProgress` throws a
 * `ClassCastException`. Defining the names here (one source of truth, referenced by every definition
 * and `get()` site) keeps the def/consumer qualifiers from drifting apart across modules.
 *
 * See `EventBusIsolationTest` for the regression guard.
 */
internal object EventBusQualifiers {
    val ScanEvents: StringQualifier = named("scanEventBus")
    val ScanResults: StringQualifier = named("scanResultBus")
    val ImportEvents: StringQualifier = named("importEventBus")
    val BackupEvents: StringQualifier = named("backupEventBus")
}
