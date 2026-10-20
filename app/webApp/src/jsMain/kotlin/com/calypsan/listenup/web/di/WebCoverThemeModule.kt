package com.calypsan.listenup.web.di

import com.calypsan.listenup.client.data.covertheme.browserCoverThemeRepository
import com.calypsan.listenup.client.domain.repository.CoverThemeRepository
import com.calypsan.listenup.web.design.coverUrl
import org.koin.core.module.Module
import org.koin.dsl.module

/** The browser's cover colour source, with web's own cover URL grammar at the smallest useful rung (C11). */
internal val webCoverThemeModule: Module =
    module {
        single<CoverThemeRepository> {
            browserCoverThemeRepository(coverUrl = { id, hash -> coverUrl(id.value, hash, width = COVER_THEME_RUNG) })
        }
    }

/** Spec §2.3 step 1: "≤ 64×64". The server rounds a `w` up to the smallest rung it has. */
private const val COVER_THEME_RUNG = 64
