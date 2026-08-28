package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Every client entry point must load device-local preferences before it draws anything.
 *
 * `SettingsRepositoryImpl` holds theme mode, auto-rewind, Wi-Fi-only downloads, haptics and the
 * rest as `StateFlow`s seeded with hard-coded defaults. The only thing that ever replaces those
 * defaults with what the reader actually chose is
 * [com.calypsan.listenup.client.domain.repository.LocalPreferences.initializeLocalPreferences] —
 * `SecureStorage.read` is suspend, so a constructor cannot do it and no lazy accessor can either.
 *
 * That makes the load a *boot step*, and a boot step is exactly the kind of thing three of four
 * platforms forgot: for a long time Android's `MainActivity` was the only caller in the repo, so
 * on web, desktop and iOS every one of those preferences was written faithfully and read back
 * never. The visible symptom was the smallest and the most damning — pick Light, reload, and the
 * app is dark again, having stored `theme_mode=light` the whole time.
 *
 * Wiring is invisible to a unit test: each of these functions is an untestable `main`. So the
 * guard is this rule, and it is deliberately two assertions rather than one. The membership check
 * fails if an entry point is renamed or moved, because a rule that quietly stops matching a file
 * stops guarding it — and this is a bug class that already recurred once by being unenforced.
 *
 * iOS's entry is the Kotlin surface Swift boots through, not a `main`: `KoinHelper` exposes the
 * suspend call and `ListenUpApp` awaits it. Konsist cannot read Swift, so this pins the half it
 * can see — and the Swift side cannot silently regress past it, because deleting the call it
 * awaits is a compile error over there.
 */
class LocalPreferencesAreLoadedAtStartupRule :
    FunSpec({

        // Path suffix → what boots there. Every client's first-run path, one entry each.
        val entryPoints =
            mapOf(
                "app/sharedUI/src/androidMain/kotlin/com/calypsan/listenup/client/MainActivity.kt" to "Android",
                "app/webApp/src/jsMain/kotlin/com/calypsan/listenup/web/Main.kt" to "web",
                "app/desktopApp/src/main/kotlin/com/calypsan/listenup/desktop/Main.kt" to "desktop",
                "app/sharedLogic/src/iosMain/kotlin/com/calypsan/listenup/client/di/Koin.ios.kt" to "iOS",
            )

        test("every client entry point still exists where this rule looks for it") {
            val missing =
                entryPoints
                    .filterKeys { path -> productionScope().files.none { it.path.endsWith(path) } }
                    .map { (path, platform) -> "$platform: $path" }

            missing.shouldBeEmpty()
        }

        test("every client entry point loads device-local preferences at startup") {
            val offenders =
                entryPoints
                    .filterKeys { path ->
                        productionScope()
                            .files
                            .filter { it.path.endsWith(path) }
                            .none { it.text.contains("initializeLocalPreferences") }
                    }.map { (path, platform) ->
                        "$platform does not call initializeLocalPreferences(): $path"
                    }

            offenders.shouldBeEmpty()
        }
    })
