package com.calypsan.listenup.server.compression.zip

/**
 * True if [name] is safe to extract under a target directory: a non-empty relative path with no absolute
 * root, no Windows drive/backslash, and no `..` segment that could escape. The extracting consumer must
 * call this before writing each entry (ZIP-slip defense).
 */
public fun isSafeEntryName(name: String): Boolean {
    if (name.isEmpty()) return false
    if (name.startsWith("/") || name.startsWith("\\")) return false
    if (name.length >= 2 && name[1] == ':') return false
    return name.split('/', '\\').none { it == ".." }
}
