package com.calypsan.listenup.client.download

private const val VERSION_2 = "v2"
private const val SEPARATOR = "|"
private const val V2_FIELDS = 4
private const val V1_MIN_FIELDS = 3

/**
 * The file a background download task is fetching, carried in the task's `taskDescription`.
 *
 * The task outlives the process inside `nsurlsessiond`, so after a relaunch this tag is the only way
 * to know which row a callback belongs to. It names the file and never a path: the app's container
 * moves on a reinstall or a restore while the task survives, so the destination is recomputed from
 * these ids each time ([DownloadFileManager.getAudioFilePath]).
 */
internal data class DownloadTaskTag(
    val bookId: String,
    val audioFileId: String,
    val filename: String,
) {
    /** `v2|bookId|audioFileId|filename`. The filename is last, so a separator inside it survives. */
    fun encode(): String = listOf(VERSION_2, bookId, audioFileId, filename).joinToString(SEPARATOR)

    companion object {
        /**
         * The tag in [description], or null when it is not one of ours. Also reads the tags of the
         * foreground-session builds (`bookId|audioFileId|filename|absolutePath`), whose path is
         * ignored.
         */
        fun parse(description: String?): DownloadTaskTag? {
            if (description.isNullOrEmpty()) return null
            val tag =
                if (description.startsWith(VERSION_2 + SEPARATOR)) {
                    val fields = description.split(SEPARATOR, limit = V2_FIELDS)
                    if (fields.size < V2_FIELDS) return null
                    DownloadTaskTag(bookId = fields[1], audioFileId = fields[2], filename = fields[3])
                } else {
                    val fields = description.split(SEPARATOR)
                    if (fields.size < V1_MIN_FIELDS) return null
                    DownloadTaskTag(bookId = fields[0], audioFileId = fields[1], filename = fields[2])
                }
            return tag.takeIf { it.bookId.isNotBlank() && it.audioFileId.isNotBlank() && it.filename.isNotBlank() }
        }
    }
}
