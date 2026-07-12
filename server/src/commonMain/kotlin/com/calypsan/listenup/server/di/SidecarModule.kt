package com.calypsan.listenup.server.di

import com.calypsan.listenup.server.scheduler.SidecarRetryTask
import com.calypsan.listenup.server.sidecar.SidecarAssembler
import com.calypsan.listenup.server.sidecar.SidecarWriteStateRepository
import com.calypsan.listenup.server.sidecar.SidecarWriter
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin module for the `listenup.json` sidecar slice (Foundation Trio Phase 2):
 * [SidecarAssembler] (pure DB→disk projection), [SidecarWriteStateRepository] (the
 * round-trip hash discriminator), [SidecarWriter] (debounced write-through via the
 * library-write broker), and [SidecarRetryTask] (the periodic pending-write sweep).
 * [applicationScope] hosts the writer's debounce jobs, matching the scheduler tasks.
 */
fun sidecarModule(applicationScope: CoroutineScope): Module =
    module {
        single { SidecarAssembler() }
        single { SidecarWriteStateRepository(get()) }
        single {
            SidecarWriter(
                db = get(),
                assembler = get(),
                broker = get(),
                writeState = get(),
                settings = get(),
                scope = applicationScope,
                clock = get(),
            )
        }
        single { SidecarRetryTask(writer = get()) }
    }
