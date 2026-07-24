package com.calypsan.listenup.client.domain.version

/** Minimal semver — major/minor/patch. Malformed input parses to null (no false `Outdated` signal from noise). */
internal data class Semver(
    val major: Int,
    val minor: Int,
    val patch: Int,
) {
    companion object {
        fun parseOrNull(raw: String): Semver? {
            // Parse POSITIONALLY and fail on the first non-numeric required segment. A previous
            // `mapNotNull` approach silently dropped non-numeric tokens ("1.x.3" → 1,3,0), shifting
            // values between slots — the opposite of the "malformed → null, no false signal" contract.
            val parts = raw.removePrefix("v").split(".", "-", "+")
            if (parts.size < 3) return null
            val major = parts[0].toIntOrNull() ?: return null
            val minor = parts[1].toIntOrNull() ?: return null
            val patch = parts[2].toIntOrNull() ?: return null
            return Semver(major, minor, patch)
        }
    }
}
