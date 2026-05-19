package com.calypsan.listenup.client.data.local.images

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for JvmStoragePaths.
 *
 * Verifies that storage paths are correctly resolved for the current platform
 * and that directories are created as expected.
 */
class JvmStoragePathsTest {
    private val storagePaths = JvmStoragePaths()

    @Test
    fun `filesDir returns non-null path`() {
        // When
        val filesDir = storagePaths.filesDir

        // Then
        assertNotNull(filesDir)
        assertTrue(filesDir.toString().isNotEmpty())
    }

    @Test
    fun `filesDir contains listenup in path`() {
        // When
        val filesDir = storagePaths.filesDir.toString().lowercase()

        // Then
        assertTrue(
            filesDir.contains("listenup"),
            "Path should contain 'listenup': $filesDir",
        )
    }

    @Test
    fun `filesDir uses appropriate base on Linux`() {
        val os = System.getProperty("os.name", "").lowercase()
        if (!os.contains("linux")) {
            println("Skipping Linux-specific test on $os")
            return
        }

        // When
        val filesDir = storagePaths.filesDir.toString()

        // Then - should use XDG_DATA_HOME or ~/.local/share
        val xdgData = System.getenv("XDG_DATA_HOME")
        val expectedBase = xdgData ?: "${System.getProperty("user.home")}/.local/share"

        assertTrue(
            filesDir.startsWith(expectedBase),
            "Linux path should start with $expectedBase: $filesDir",
        )
    }

    @Test
    fun `filesDir uses appropriate base on Windows`() {
        val os = System.getProperty("os.name", "").lowercase()
        if (!os.contains("windows")) {
            println("Skipping Windows-specific test on $os")
            return
        }

        // When
        val filesDir = storagePaths.filesDir.toString()

        // Then - should use APPDATA
        val appData =
            System.getenv("APPDATA")
                ?: "${System.getProperty("user.home")}/AppData/Roaming"

        assertTrue(
            filesDir.startsWith(appData),
            "Windows path should start with $appData: $filesDir",
        )
    }

    @Test
    fun `getDatabaseDirectory returns valid path`() {
        // When
        val dbDir = storagePaths.getDatabaseDirectory()

        // Then
        assertNotNull(dbDir)
        assertTrue(dbDir.absolutePath.contains("data"), "DB dir should contain 'data': ${dbDir.absolutePath}")
    }

    @Test
    fun `getDatabasePath returns path ending in listenup db`() {
        // When
        val dbPath = storagePaths.getDatabasePath()

        // Then
        assertTrue(
            dbPath.endsWith("listenup.db"),
            "DB path should end with 'listenup.db': $dbPath",
        )
    }

    @Test
    fun `getSecureStoragePath returns path ending in auth enc`() {
        // When
        val authPath = storagePaths.getSecureStoragePath()

        // Then
        assertTrue(
            authPath.absolutePath.endsWith("auth.enc"),
            "Auth path should end with 'auth.enc': ${authPath.absolutePath}",
        )
    }

    @Test
    fun `getCacheDirectory returns valid path`() {
        // When
        val cacheDir = storagePaths.getCacheDirectory()

        // Then
        assertNotNull(cacheDir)
        assertTrue(cacheDir.absolutePath.isNotEmpty())
    }

    @Test
    fun `getCacheDirectory uses appropriate base on Linux`() {
        val os = System.getProperty("os.name", "").lowercase()
        if (!os.contains("linux")) {
            println("Skipping Linux-specific test on $os")
            return
        }

        // When
        val cacheDir = storagePaths.getCacheDirectory().absolutePath

        // Then - should use XDG_CACHE_HOME or ~/.cache
        val xdgCache = System.getenv("XDG_CACHE_HOME")
        val expectedBase = xdgCache ?: "${System.getProperty("user.home")}/.cache"

        assertTrue(
            cacheDir.startsWith(expectedBase),
            "Linux cache path should start with $expectedBase: $cacheDir",
        )
    }

    @Test
    fun `directories are created when accessed`() {
        // Given - a fresh instance
        val paths = JvmStoragePaths()

        // When - access filesDir
        val filesDir = paths.filesDir

        // Then - directory should exist (created lazily)
        val file = java.io.File(filesDir.toString())
        assertTrue(file.exists(), "filesDir should be created: $filesDir")
        assertTrue(file.isDirectory, "filesDir should be a directory")
    }

    @Test
    fun `database directory is created when accessed`() {
        // When
        val dbDir = storagePaths.getDatabaseDirectory()

        // Then
        assertTrue(dbDir.exists(), "Database directory should be created")
        assertTrue(dbDir.isDirectory, "Database directory should be a directory")
    }

    @Test
    fun `cache directory is created when accessed`() {
        // When
        val cacheDir = storagePaths.getCacheDirectory()

        // Then
        assertTrue(cacheDir.exists(), "Cache directory should be created")
        assertTrue(cacheDir.isDirectory, "Cache directory should be a directory")
    }

    @Test
    fun `paths do not contain double slashes`() {
        // When
        val filesDir = storagePaths.filesDir.toString()
        val dbPath = storagePaths.getDatabasePath()
        val authPath = storagePaths.getSecureStoragePath().absolutePath
        val cachePath = storagePaths.getCacheDirectory().absolutePath

        // Then - no double slashes (path construction error)
        assertTrue(!filesDir.contains("//"), "filesDir has double slashes: $filesDir")
        assertTrue(!dbPath.contains("//"), "dbPath has double slashes: $dbPath")
        assertTrue(!authPath.contains("//"), "authPath has double slashes: $authPath")
        assertTrue(!cachePath.contains("//"), "cachePath has double slashes: $cachePath")
    }
}
