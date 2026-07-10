package com.calypsan.listenup.client.domain.version

/** Minimal semver — major/minor/patch. Malformed input parses to null (no false `Outdated` signal from noise). */
internal data class Semver(
    val major: Int,
    val minor: Int,
    val patch: Int,
) {
    companion object {
        fun parseOrNull(raw: String): Semver? {
            val parts = raw.removePrefix("v").split(".", "-", "+")
            val nums = parts.take(3).mapNotNull { it.toIntOrNull() }
            return if (nums.isNotEmpty()) {
                Semver(nums.getOrElse(0) { 0 }, nums.getOrElse(1) { 0 }, nums.getOrElse(2) { 0 })
            } else {
                null
            }
        }
    }
}
