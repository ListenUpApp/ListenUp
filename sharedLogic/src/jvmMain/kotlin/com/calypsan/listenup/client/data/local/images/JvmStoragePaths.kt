package com.calypsan.listenup.client.data.local.images

import kotlinx.io.files.Path
import java.io.File

/**
 * JVM desktop implementation of [StoragePaths].
 *
 * Uses platform-appropriate app data directories:
 * - Windows: %APPDATA%/ListenUp
 * - Linux: ~/.local/share/listenup (XDG Base Directory Specification)
 *
 * Creates directories if they don't exist.
 */
class JvmStoragePaths : StoragePaths {
    override val filesDir: Path by lazy {
        val dir = getAppDataDirectory()
        dir.mkdirs()
        Path(dir.absolutePath)
    }

    /**
     * Gets the platform-appropriate application data directory.
     */
    private fun getAppDataDirectory(): File {
        val os = System.getProperty("os.name", "").lowercase()
        return when {
            "windows" in os -> {
                val appData =
                    System.getenv("APPDATA")
                        ?: "${System.getProperty("user.home")}/AppData/Roaming"
                File(appData, "ListenUp")
            }

            else -> {
                // Linux and others: XDG Base Directory Specification
                val xdgData =
                    System.getenv("XDG_DATA_HOME")
                        ?: "${System.getProperty("user.home")}/.local/share"
                File(xdgData, "listenup")
            }
        }
    }

    /**
     * Gets the database directory path.
     * Database stored at: {appDataDir}/data/
     */
    fun getDatabaseDirectory(): File {
        val dbDir = File(filesDir.toString(), "data")
        dbDir.mkdirs()
        return dbDir
    }

    /**
     * Gets the full database file path.
     */
    fun getDatabasePath(): String = File(getDatabaseDirectory(), "listenup.db").absolutePath

    /**
     * Gets the secure storage file path for encrypted credentials.
     */
    fun getSecureStoragePath(): File = File(filesDir.toString(), "auth.enc")

    /**
     * Gets the cache directory for temporary files.
     * - Windows: %LOCALAPPDATA%/ListenUp/cache
     * - Linux: ~/.cache/listenup
     */
    fun getCacheDirectory(): File {
        val os = System.getProperty("os.name", "").lowercase()
        val cacheDir =
            when {
                "windows" in os -> {
                    val localAppData =
                        System.getenv("LOCALAPPDATA")
                            ?: "${System.getProperty("user.home")}/AppData/Local"
                    File(localAppData, "ListenUp/cache")
                }

                else -> {
                    val xdgCache =
                        System.getenv("XDG_CACHE_HOME")
                            ?: "${System.getProperty("user.home")}/.cache"
                    File(xdgCache, "listenup")
                }
            }
        cacheDir.mkdirs()
        return cacheDir
    }
}
