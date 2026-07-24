package com.calypsan.listenup.client.data.local.images

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Tests for JvmStoragePaths.
 *
 * Verifies that storage paths are correctly resolved for the current platform
 * and that directories are created as expected.
 */
class JvmStoragePathsTest :
    FunSpec({
        val storagePaths = JvmStoragePaths()

        test("filesDir returns non-null path") {
            // When
            val filesDir = storagePaths.filesDir

            // Then
            filesDir shouldNotBe null
            filesDir.toString().isNotEmpty() shouldBe true
        }

        test("filesDir contains listenup in path") {
            // When
            val filesDir = storagePaths.filesDir.toString().lowercase()

            // Then
            withClue("Path should contain 'listenup': $filesDir") {
                filesDir.contains("listenup") shouldBe true
            }
        }

        test("filesDir uses appropriate base on Linux") {
            val os = System.getProperty("os.name", "").lowercase()
            if (!os.contains("linux")) {
                println("Skipping Linux-specific test on $os")
                return@test
            }

            // When
            val filesDir = storagePaths.filesDir.toString()

            // Then - should use XDG_DATA_HOME or ~/.local/share
            val xdgData = System.getenv("XDG_DATA_HOME")
            val expectedBase = xdgData ?: "${System.getProperty("user.home")}/.local/share"

            withClue("Linux path should start with $expectedBase: $filesDir") {
                filesDir.startsWith(expectedBase) shouldBe true
            }
        }

        test("filesDir uses appropriate base on Windows") {
            val os = System.getProperty("os.name", "").lowercase()
            if (!os.contains("windows")) {
                println("Skipping Windows-specific test on $os")
                return@test
            }

            // When
            val filesDir = storagePaths.filesDir.toString()

            // Then - should use APPDATA
            val appData =
                System.getenv("APPDATA")
                    ?: "${System.getProperty("user.home")}/AppData/Roaming"

            withClue("Windows path should start with $appData: $filesDir") {
                filesDir.startsWith(appData) shouldBe true
            }
        }

        test("getDatabaseDirectory returns valid path") {
            // When
            val dbDir = storagePaths.getDatabaseDirectory()

            // Then
            dbDir shouldNotBe null
            withClue("DB dir should contain 'data': ${dbDir.absolutePath}") {
                dbDir.absolutePath.contains("data") shouldBe true
            }
        }

        test("getDatabasePath returns path ending in listenup db") {
            // When
            val dbPath = storagePaths.getDatabasePath()

            // Then
            withClue("DB path should end with 'listenup.db': $dbPath") {
                dbPath.endsWith("listenup.db") shouldBe true
            }
        }

        test("getSecureStoragePath returns path ending in auth enc") {
            // When
            val authPath = storagePaths.getSecureStoragePath()

            // Then
            withClue("Auth path should end with 'auth.enc': ${authPath.absolutePath}") {
                authPath.absolutePath.endsWith("auth.enc") shouldBe true
            }
        }

        test("getCacheDirectory returns valid path") {
            // When
            val cacheDir = storagePaths.getCacheDirectory()

            // Then
            cacheDir shouldNotBe null
            cacheDir.absolutePath.isNotEmpty() shouldBe true
        }

        test("getCacheDirectory uses appropriate base on Linux") {
            val os = System.getProperty("os.name", "").lowercase()
            if (!os.contains("linux")) {
                println("Skipping Linux-specific test on $os")
                return@test
            }

            // When
            val cacheDir = storagePaths.getCacheDirectory().absolutePath

            // Then - should use XDG_CACHE_HOME or ~/.cache
            val xdgCache = System.getenv("XDG_CACHE_HOME")
            val expectedBase = xdgCache ?: "${System.getProperty("user.home")}/.cache"

            withClue("Linux cache path should start with $expectedBase: $cacheDir") {
                cacheDir.startsWith(expectedBase) shouldBe true
            }
        }

        test("directories are created when accessed") {
            // Given - a fresh instance
            val paths = JvmStoragePaths()

            // When - access filesDir
            val filesDir = paths.filesDir

            // Then - directory should exist (created lazily)
            val file = java.io.File(filesDir.toString())
            withClue("filesDir should be created: $filesDir") {
                file.exists() shouldBe true
            }
            withClue("filesDir should be a directory") {
                file.isDirectory shouldBe true
            }
        }

        test("database directory is created when accessed") {
            // When
            val dbDir = storagePaths.getDatabaseDirectory()

            // Then
            withClue("Database directory should be created") {
                dbDir.exists() shouldBe true
            }
            withClue("Database directory should be a directory") {
                dbDir.isDirectory shouldBe true
            }
        }

        test("cache directory is created when accessed") {
            // When
            val cacheDir = storagePaths.getCacheDirectory()

            // Then
            withClue("Cache directory should be created") {
                cacheDir.exists() shouldBe true
            }
            withClue("Cache directory should be a directory") {
                cacheDir.isDirectory shouldBe true
            }
        }

        test("paths do not contain double slashes") {
            // When
            val filesDir = storagePaths.filesDir.toString()
            val dbPath = storagePaths.getDatabasePath()
            val authPath = storagePaths.getSecureStoragePath().absolutePath
            val cachePath = storagePaths.getCacheDirectory().absolutePath

            // Then - no double slashes (path construction error)
            withClue("filesDir has double slashes: $filesDir") {
                !filesDir.contains("//") shouldBe true
            }
            withClue("dbPath has double slashes: $dbPath") {
                !dbPath.contains("//") shouldBe true
            }
            withClue("authPath has double slashes: $authPath") {
                !authPath.contains("//") shouldBe true
            }
            withClue("cachePath has double slashes: $cachePath") {
                !cachePath.contains("//") shouldBe true
            }
        }
    })
