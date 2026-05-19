package com.calypsan.listenup.core

/**
 * `true` when the app was built in a debug/development configuration; `false` in release.
 *
 * Used to gate verbose logging, diagnostic overlays, and other development-only behaviour
 * out of production builds. Resolved per-platform:
 *
 * - Android: `BuildConfig.DEBUG` (Android Gradle plugin derives this from the build type).
 * - Desktop (JVM): reads the `listenup.debug` system property; defaults to `false`.
 * - iOS / macOS (Apple): reads the `LISTENUP_DEBUG` environment variable; defaults to `false`.
 *
 * Keep uses few and intentional — burying production behaviour behind a debug flag is a
 * smell. See Finding 04 D6 for motivation.
 */
expect val isDebugBuild: Boolean
