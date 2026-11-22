package com.calypsan.listenup.server.transcode

/**
 * Builds the external-process argv for one encoder run. Pure, so the whole decision table is a unit
 * test rather than something only a real transcode would reveal.
 *
 * ⛔ **Some sources must not be decoded by FFmpeg.** Its native xHE-AAC (USAC) decoder is incomplete
 * at `ff_aac_parse_fac_data` — the data USAC needs at every switch between its speech coder and its
 * transform coder, which narrated speech does constantly. It drops the packets it cannot parse,
 * writes short segments, and **exits 0**. Measured across 61 files sampled from a real library:
 * every one affected, a median of 23% of the audio silently gone. Fraunhofer FDK decodes them
 * exactly, so those sources take a three-stage detour and everything else does not.
 */
object TranscodeCommand {
    /** Stereo is the safe assumption when a file has no channel count recorded. */
    const val FALLBACK_CHANNELS = 2

    /** FDK's limiter look-ahead for USAC — see [fdkLookaheadSamples]. */
    private const val FDK_USAC_LIMITER_LOOKAHEAD_MS = 5L

    private const val MILLIS_PER_SECOND = 1_000L

    /** Quiet, non-interactive, warnings only — the same posture on every stage. */
    private val BASE = listOf("-nostdin", "-hide_banner", "-loglevel", "warning")

    /**
     * Whether FFmpeg's own decoder can be trusted with this source.
     *
     * Deliberately narrow: only xHE-AAC is known to be mis-decoded, and routing anything else
     * through the detour would cost two extra processes for nothing.
     */
    fun requiresExternalDecoder(
        codec: String,
        profile: String?,
    ): Boolean = codec.equals("aac", ignoreCase = true) && profile?.lowercase() == "xhe"

    /**
     * The pipeline for one run, or **null** when this source needs the external decoder and none is
     * installed. Null is a refusal, not an error: producing audio that is quietly missing a fifth of
     * the book would be far worse than declining to produce any.
     */
    fun forSession(
        ffmpegPath: String,
        decoderPath: String?,
        session: TranscodeSession,
        startSeconds: Double,
        plan: HlsPlaylist.Plan,
        outputPattern: String,
        runListPath: String,
        bitrateKbps: Int,
    ): List<List<String>>? {
        val encode =
            encodeArgs(
                plan = plan,
                outputPattern = outputPattern,
                runListPath = runListPath,
                session = session,
                bitrateKbps = bitrateKbps,
            )
        if (!requiresExternalDecoder(session.codec, session.codecProfile)) {
            return listOf(listOf(ffmpegPath) + BASE + seekAndOpen(session, startSeconds) + encode)
        }
        if (decoderPath == null) return null
        return listOf(
            // Demux and seek only. `-c:a copy` moves the original frames without ever handing them
            // to the decoder that cannot read them, which is why the seek stays cheap on a long book.
            listOf(ffmpegPath) + BASE + seekAndOpen(session, startSeconds) +
                // `pipe:` rather than a bare `-`: the minimal FFmpeg this image builds resolves the
                // explicit protocol name, while `-` needs a mapping that a `--disable-everything`
                // build does not carry ("Protocol not found. Did you mean file:fd:?").
                listOf("-c:a", "copy", "-f", "matroska", "pipe:"),
            // Matroska is the only intermediate that survives this: `-f latm` refuses USAC outright
            // ("Muxing MPEG-4 AOT 42 in LATM is not supported"), and fragmented MP4 over a pipe
            // fails in qtdemux because its moof size is unknowable on a non-seekable stream.
            decoderPipeline(decoderPath, session),
            listOf(ffmpegPath) + BASE + rawPcmInput(session) + exactRun(session, plan) + encode,
        )
    }

    /**
     * Cuts FDK's output to exactly the audio the playlist declares for this run.
     *
     * ⛔ **FFmpeg trims a decode to the container's timeline; FDK through GStreamer trims nothing.**
     * Its output starts [fdkLookaheadSamples] late, and it ends with every padded sample of the
     * source's final frame plus whatever the decoder drains. All of that surplus lands in the last
     * segment, which then holds more frames than its `#EXTINF` promised and fails verification on
     * every request — a book whose last segment can never be served (#1742). Counting from the
     * front of the decoded stream, then, is what makes the frame check pass exactly rather than
     * by a widened tolerance: whole segments stay whole and the last one holds its remainder.
     *
     * The start is not perfectly anchored. A seek lands on the demuxer's pre-roll before the
     * requested point, and the source's own priming is still in the stream, so a run can begin
     * early by that much (46 to 140 ms on the test fixtures) and end early by the same amount.
     * Neither changes a segment's frame count. Anchoring it needs the first packet's exact
     * timestamp, which this pipeline loses: Matroska carries it to the millisecond, and GStreamer
     * rebases its output to zero.
     */
    private fun exactRun(
        session: TranscodeSession,
        plan: HlsPlaylist.Plan,
    ): List<String> {
        val start = fdkLookaheadSamples(session.sampleRate)
        val end = start + plan.samplesFrom(session.startSegment)
        return listOf("-af", "atrim=start_sample=$start:end_sample=$end")
    }

    /**
     * How late FDK's USAC output runs: its peak limiter looks [FDK_USAC_LIMITER_LOOKAHEAD_MS] ahead,
     * which it reports as `CStreamInfo.outputDelay` and GStreamer's `fdkaacdec` never applies. Set by
     * `pcmLimiter_SetAttack` for USAC in fdk-aac's `libAACdec/src/aacdecoder.cpp`; USAC adds no SBR,
     * MPS or concealment delay on top. Measured: exactly 220 samples at 44.1 kHz, with and without SBR.
     */
    internal fun fdkLookaheadSamples(sampleRate: Int): Long = sampleRate.toLong() * FDK_USAC_LIMITER_LOOKAHEAD_MS / MILLIS_PER_SECOND

    private fun seekAndOpen(
        session: TranscodeSession,
        startSeconds: Double,
    ): List<String> =
        listOf(
            // -ss BEFORE -i seeks by index rather than decoding to the point: the difference between
            // starting a 90-hour book's last chapter in a second and in a minute.
            "-ss",
            HlsPlaylist.formatSeconds(startSeconds),
            "-i",
            session.sourcePath,
            "-vn",
            "-map",
            "0:a:0",
        )

    private fun decoderPipeline(
        decoderPath: String,
        session: TranscodeSession,
    ): List<String> =
        listOf(
            decoderPath,
            "-q",
            "fdsrc",
            "fd=0",
            "!",
            "matroskademux",
            "name=d",
            "d.audio_0",
            "!",
            "queue",
            "!",
            "fdkaacdec",
            "!",
            "audioconvert",
            "!",
            "audio/x-raw,format=S16LE,rate=${session.sampleRate},channels=${session.channels}",
            "!",
            "fdsink",
            "fd=1",
        )

    /** Raw PCM carries no header, so the encoder has to be told exactly what it is being handed. */
    private fun rawPcmInput(session: TranscodeSession): List<String> =
        listOf(
            "-f",
            "s16le",
            "-ar",
            session.sampleRate.toString(),
            "-ac",
            session.channels.toString(),
            "-i",
            "pipe:",
        )

    private fun encodeArgs(
        plan: HlsPlaylist.Plan,
        outputPattern: String,
        runListPath: String,
        session: TranscodeSession,
        bitrateKbps: Int,
    ): List<String> =
        listOf(
            "-c:a",
            "aac",
            "-b:a",
            "${bitrateKbps}k",
            // ⛔ Never resample: HlsPlaylist's frame math is computed from the SOURCE rate, and an
            // output at a different rate makes every declared EXTINF describe a file we did not write.
            "-ar",
            session.sampleRate.toString(),
            "-f",
            "segment",
            "-segment_format",
            "adts",
            "-segment_time",
            HlsPlaylist.formatSeconds(plan.segmentSeconds),
            "-segment_start_number",
            session.startSegment.toString(),
            // The muxer's own completion signal. A segment file exists from the moment it is opened,
            // so this list — appended to as each segment is CLOSED — is the only thing that can tell
            // a waiting request that the last segment of this run is whole. `+live` flushes per entry
            // rather than at exit, which is what makes it useful while the encode is still running.
            "-segment_list",
            runListPath,
            "-segment_list_type",
            "flat",
            "-segment_list_flags",
            "+live",
            outputPattern,
        )
}
