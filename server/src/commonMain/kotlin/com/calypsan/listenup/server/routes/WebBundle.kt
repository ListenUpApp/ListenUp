package com.calypsan.listenup.server.routes

import com.calypsan.listenup.server.io.fileIoDispatcher
import com.calypsan.listenup.server.io.hashBytesSha256
import com.calypsan.listenup.server.io.readBytes
import io.ktor.http.ContentType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * How [WebBundle] reaches the disk. A seam so a test can count the visits — the whole point of the
 * cache is that a warm request makes none.
 */
internal interface WebBundleFiles {
    /** Whether [path] exists and is a regular file (not a directory). */
    fun isRegularFile(path: Path): Boolean

    /** The whole file at [path]. */
    fun readBytes(path: Path): ByteArray
}

/** The real filesystem, through kotlinx-io so it works on JVM and linuxX64 alike. */
internal object SystemWebBundleFiles : WebBundleFiles {
    override fun isRegularFile(path: Path): Boolean = SystemFileSystem.metadataOrNull(path)?.isRegularFile == true

    override fun readBytes(path: Path): ByteArray = path.readBytes()
}

/** One way of sending a bundle file: its bytes on disk, and the ETag that names them. */
internal class Representation(
    val encoding: BundleEncoding,
    val path: Path,
    val etag: String,
    /** Held in memory when small enough; otherwise read at send time. */
    val inMemory: ByteArray?,
)

/** A bundle file and every representation of it the build produced. */
internal class BundleFile(
    val contentType: ContentType,
    val representations: Map<BundleEncoding, Representation>,
)

/**
 * The web bundle on disk, with each file's ETags — and, for small files, its bytes — computed once
 * and then served from memory.
 *
 * **Why caching is sound:** the bundle is immutable for the life of the process. It is baked into
 * the image, and a deploy is a new container. A rebuilt `dist/` under a *running* dev server is
 * the one case this misses; restart the server after rebuilding.
 *
 * **What is bounded:** only real files are cached, so the map can grow no larger than the bundle
 * itself. A miss — a client-side route such as `/library/some-book`, or a scan for `/wp-admin` —
 * costs a stat and is not remembered, or anyone could grow the map with made-up URLs. Files over
 * [inMemoryLimit] keep only their ETag: the raw main script is ~16 MB and almost never requested
 * raw, while its brotli sibling is a fraction of that and requested by nearly everyone.
 *
 * ETags derive from the raw file's SHA-256 plus [BundleEncoding.etagSuffix]: the variants are made
 * from the raw file in the same build, so the raw hash names all of them, and the suffix keeps a
 * cache from revalidating brotli bytes for a client that asked for gzip.
 */
internal class WebBundle(
    private val root: Path,
    private val files: WebBundleFiles,
    private val inMemoryLimit: Int = DEFAULT_IN_MEMORY_LIMIT,
) {
    private val mutex = Mutex()
    private val cache = HashMap<String, BundleFile>()

    /** The file at [segments] under the root, or null when there is no such regular file. */
    suspend fun lookup(segments: List<String>): BundleFile? {
        val key = segments.joinToString("/")
        mutex.withLock { cache[key] }?.let { return it }

        // Loaded outside the lock so a cold 16 MB read does not stall every warm request behind it.
        // Two concurrent first requests may both load; the loads are identical and the first wins.
        val loaded = withContext(fileIoDispatcher) { load(segments) } ?: return null
        return mutex.withLock { cache.getOrPut(key) { loaded } }
    }

    /** The bytes to send for [representation]: from memory when held, otherwise from disk. */
    suspend fun bytesOf(representation: Representation): ByteArray =
        representation.inMemory ?: withContext(fileIoDispatcher) { files.readBytes(representation.path) }

    private fun load(segments: List<String>): BundleFile? {
        val path = segments.fold(root) { acc, segment -> Path(acc, segment) }
        if (!files.isRegularFile(path)) return null

        val raw = files.readBytes(path)
        val hash = hashBytesSha256(raw)
        val representations =
            buildMap {
                put(BundleEncoding.IDENTITY, representation(BundleEncoding.IDENTITY, path, hash, raw))
                for (encoding in BundleEncoding.compressed) {
                    val variant = Path(path.parent ?: root, path.name + encoding.fileSuffix)
                    if (!files.isRegularFile(variant)) continue
                    put(encoding, representation(encoding, variant, hash, files.readBytes(variant)))
                }
            }
        return BundleFile(contentTypeFor(path.name), representations)
    }

    private fun representation(
        encoding: BundleEncoding,
        path: Path,
        rawHash: String,
        bytes: ByteArray,
    ) = Representation(
        encoding = encoding,
        path = path,
        etag = "\"$rawHash${encoding.etagSuffix}\"",
        inMemory = bytes.takeIf { it.size <= inMemoryLimit },
    )

    private companion object {
        const val DEFAULT_IN_MEMORY_LIMIT = 4 * 1024 * 1024
    }
}

/**
 * Maps the extensions a Vite build actually emits.
 *
 * `.wasm` matters most: served as anything else the browser refuses to stream-compile it, and
 * the SQLite build silently falls back or fails. A precompressed variant is sent under its
 * ORIGINAL file's type — this is only ever asked about the original name.
 */
private fun contentTypeFor(name: String): ContentType =
    when (name.substringAfterLast('.', "")) {
        "html" -> ContentType.Text.Html
        "js", "mjs" -> ContentType.Application.JavaScript
        "css" -> ContentType.Text.CSS
        "wasm" -> ContentType("application", "wasm")
        "json", "map" -> ContentType.Application.Json
        "svg" -> ContentType.Image.SVG
        "png" -> ContentType.Image.PNG
        "woff2" -> ContentType("font", "woff2")
        else -> ContentType.Application.OctetStream
    }
