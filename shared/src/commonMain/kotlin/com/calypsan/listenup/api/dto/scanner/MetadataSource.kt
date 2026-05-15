package com.calypsan.listenup.api.dto.scanner

import kotlinx.serialization.Serializable

/**
 * Where a field on [AnalyzedBook] came from. The Analyzer records every
 * source that contributed so consumers can reason about precedence — and
 * so a debug UI can later show "this title came from `metadata.json`,
 * this author came from the folder structure."
 */
@Serializable
enum class MetadataSource {
    FOLDER_STRUCTURE,
    FILENAME,
    ABS_METADATA,
    OPF_FILE,
    NFO_FILE,
    TXT_FILES,
    SIDECAR,
    AUDIO_METATAGS,
}
