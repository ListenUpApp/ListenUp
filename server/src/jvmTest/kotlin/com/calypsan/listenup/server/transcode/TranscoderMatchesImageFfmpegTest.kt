package com.calypsan.listenup.server.transcode

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import java.io.File

/**
 * Every FFmpeg command this server runs may use only what the image's FFmpeg is built with.
 *
 * ⛔ **The host's FFmpeg cannot catch this.** `server/Dockerfile.native` builds FFmpeg with
 * `--disable-everything` and enables components one by one, while every test that runs a real
 * FFmpeg runs the developer's or the CI runner's full build. The AAC probe once wrote to the `null`
 * muxer, which the image never enabled: it passed everywhere it was tested and switched transcoding
 * off on every image-based server. This spec reads the configure flags out of the Dockerfile and
 * checks each command's components against them, so a command and the image cannot drift apart.
 */
class TranscoderMatchesImageFfmpegTest :
    FunSpec({
        val enabled = enabledComponents(File(Konsist.projectRootPath, "server/Dockerfile.native").readText())

        test("the Dockerfile's FFmpeg configure flags are found") {
            COMPONENT_KINDS.forEach { kind -> enabled[kind].orEmpty().shouldNotBeEmpty() }
        }

        test("the AAC probe uses only components the image's FFmpeg is built with") {
            missingComponents(aacProbeCommand(FFMPEG), enabled).shouldBeEmpty()
        }

        test("every FFmpeg stage of a transcode uses only components the image's FFmpeg is built with") {
            val stages =
                listOf("lc", "xhe").flatMap { profile ->
                    TranscodeCommand
                        .forSession(
                            ffmpegPath = FFMPEG,
                            decoderPath = DECODER,
                            session = session(profile),
                            startSeconds = 0.0,
                            plan = HlsPlaylist.plan(DURATION_MS, SAMPLE_RATE, SEGMENT_SECONDS),
                            outputPattern = "/cache/seg%05d.aac",
                            runListPath = "/cache/runs/from00000.list",
                            bitrateKbps = 64,
                        ).orEmpty()
                        .filter { it.first() == FFMPEG }
                }

            stages.shouldNotBeEmpty()
            stages.flatMap { missingComponents(it, enabled) }.shouldBeEmpty()
        }
    })

private const val FFMPEG = "/usr/local/bin/ffmpeg"
private const val DECODER = "/usr/local/bin/gst-launch-1.0"
private const val SAMPLE_RATE = 44_100
private const val DURATION_MS = 600_000L
private const val SEGMENT_SECONDS = 10

private val COMPONENT_KINDS = listOf("encoder", "muxer", "demuxer", "protocol")

/** FFmpeg's `-f` names for formats whose configure component is named differently. */
private val CONFIGURE_NAME = mapOf("s16le" to "pcm_s16le")

private fun session(profile: String) =
    TranscodeSession(
        bookId = "b1",
        fileId = "f1",
        sourcePath = "/library/book/01.m4b",
        sampleRate = SAMPLE_RATE,
        durationMs = DURATION_MS,
        startSegment = 0,
        codec = "aac",
        codecProfile = profile,
        channels = 2,
    )

/** `--enable-muxer=a,b` and friends, keyed by component kind. */
private fun enabledComponents(dockerfile: String): Map<String, Set<String>> =
    Regex("""--enable-(\w+)=([\w,]+)""")
        .findAll(dockerfile)
        .groupBy({ it.groupValues[1] }, { it.groupValues[2].split(',') })
        .mapValues { (_, lists) -> lists.flatten().toSet() }

/**
 * The components [command] needs that [enabled] does not list, as `kind=name` strings.
 *
 * Options before an `-i` apply to that input, and those after the last `-i` to the output — the
 * same positional rule FFmpeg itself applies.
 */
private fun missingComponents(
    command: List<String>,
    enabled: Map<String, Set<String>>,
): List<String> {
    val lastInput = command.lastIndexOf("-i")
    val needed =
        command.zipWithNext().withIndex().mapNotNull { (index, pair) ->
            val (option, value) = pair
            when {
                option == "-f" && index < lastInput -> "demuxer" to value
                option == "-f" || option == "-segment_format" -> "muxer" to value
                option == "-c:a" && value != "copy" -> "encoder" to value
                option == "-i" -> "protocol" to protocolOf(value)
                else -> null
            }
        } + ("protocol" to protocolOf(command.last()))
    return needed
        .map { (kind, name) -> kind to (CONFIGURE_NAME[name] ?: name) }
        .filterNot { (kind, name) -> name in enabled[kind].orEmpty() }
        .map { (kind, name) -> "${command.joinToString(" ")} needs $kind=$name" }
}

private fun protocolOf(url: String): String = if (url == "-" || url.startsWith("pipe:")) "pipe" else "file"
