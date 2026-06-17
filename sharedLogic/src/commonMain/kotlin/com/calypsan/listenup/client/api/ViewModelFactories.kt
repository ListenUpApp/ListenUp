package com.calypsan.listenup.client.api

import com.calypsan.listenup.client.presentation.auth.LoginViewModel
import com.calypsan.listenup.client.presentation.auth.RegisterViewModel
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailViewModel
import com.calypsan.listenup.client.presentation.connect.ServerConnectViewModel
import com.calypsan.listenup.client.presentation.connect.ServerSelectViewModel
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailViewModel
import com.calypsan.listenup.client.presentation.library.LibraryViewModel
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailViewModel
import org.koin.mp.KoinPlatform

/**
 * Swift-facing factory entry points for the iOS app.
 *
 * Koin is resolved internally so the DI container never crosses the Swift Export boundary.
 * (Swift Export must not export the DI graph — JetBrains-confirmed anti-pattern.)
 * One top-level function per ViewModel the iOS layer constructs; each mirrors that VM's Koin
 * definition. All VMs here are registered as `factory` with no `parametersOf` arguments.
 *
 * iOS cutover: replace `KoinHelper.shared.get*ViewModel()` call sites in Dependencies.swift
 * with these functions once the Swift Export migration is complete.
 */

/** Returns a new [ServerConnectViewModel] resolved from the Koin container. */
fun createServerConnectViewModel(): ServerConnectViewModel =
    KoinPlatform.getKoin().get()

/** Returns a new [ServerSelectViewModel] resolved from the Koin container. */
fun createServerSelectViewModel(): ServerSelectViewModel =
    KoinPlatform.getKoin().get()

/** Returns a new [LoginViewModel] resolved from the Koin container. */
fun createLoginViewModel(): LoginViewModel =
    KoinPlatform.getKoin().get()

/** Returns a new [RegisterViewModel] resolved from the Koin container. */
fun createRegisterViewModel(): RegisterViewModel =
    KoinPlatform.getKoin().get()

/** Returns a new [LibraryViewModel] resolved from the Koin container. */
fun createLibraryViewModel(): LibraryViewModel =
    KoinPlatform.getKoin().get()

/** Returns a new [BookDetailViewModel] resolved from the Koin container. */
fun createBookDetailViewModel(): BookDetailViewModel =
    KoinPlatform.getKoin().get()

/** Returns a new [SeriesDetailViewModel] resolved from the Koin container. */
fun createSeriesDetailViewModel(): SeriesDetailViewModel =
    KoinPlatform.getKoin().get()

/** Returns a new [ContributorDetailViewModel] resolved from the Koin container. */
fun createContributorDetailViewModel(): ContributorDetailViewModel =
    KoinPlatform.getKoin().get()
