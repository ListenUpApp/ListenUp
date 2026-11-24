package com.calypsan.listenup.client.di

/**
 * All presentation modules combined.
 */
internal val allPresentationModules =
    listOf(
        authPresentationModule,
        adminPresentationModule,
        libraryPresentationModule,
        bookPresentationModule,
        seriesPresentationModule,
        contributorPresentationModule,
        discoverPresentationModule,
        shelfPresentationModule,
        browsePresentationModule,
        profilePresentationModule,
        notificationsPresentationModule,
        settingsPresentationModule,
        startupPresentationModule,
    )
