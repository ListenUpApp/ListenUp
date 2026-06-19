# `embeddedmeta` — embedded audio metadata parsers

Reads tags, chapters, duration, and cover artwork from the bytes of an audio
file. Server-only, JVM-only. Domain types (`EmbeddedAudioMetadata`,
`AudioFormat`, `AudioTags`, `Chapter`, `EmbeddedArtwork`,
`SeriesEntry`, `ChapterSource`) live in `commonMain` so Phase 4's books
domain can return them on RPC service signatures without a wire-shape
duplicate.

## What's here today

Registry-dispatched parsers, one class per format:

| Format | Magic | Parser                      | Notes |
| ------ | ----- | --------------------------- | ----- |
| MP3    | `ID3` / sync | [`format/mp3/Mp3Parser.kt`](format/mp3/Mp3Parser.kt) | ID3v2 + ID3v1 + APIC + CHAP + duration. VBR (Xing/VBRI) deferred. |
| MP4    | `ftyp`       | [`format/mp4/Mp4Parser.kt`](format/mp4/Mp4Parser.kt) | atom walker + `ilst` + freeform `----` + `covr` + Nero `chpl` + Apple text-track. |

Detector recognises FLAC, Ogg, and Opus magic too — files of those formats
surface as `AudioMetadataError.UnsupportedFormat(format)` and land in the
scan summary's `unsupportedFormats` bucket. They're not crashes, not typed
errors — they're enrichment deferral. **A parser for any of them is a
single-class drop-in** following the flow below.

## Adding a new audio format

1. **Detector signature.** Add the format's magic-byte signature to
   [`AudioFormatDetector`](AudioFormatDetector.kt) if it isn't already
   recognised. Add a variant to
   [`AudioFormat`](../../../../../../../shared/src/commonMain/kotlin/com/calypsan/listenup/domain/embeddedmeta/AudioFormat.kt)
   in commonMain if the format is genuinely new (most won't be — the
   detector knows the common formats today).
2. **Parser implementation.** Create `<Format>Parser.kt` under
   `format/<name>/` implementing
   [`AudioFormatParser`](AudioFormatParser.kt). Single file. Reference
   `Mp3Parser` or `Mp4Parser` for the kotlinx-io binary-decoding shape,
   error mapping (`AudioMetadataError.IoError` /
   `AudioMetadataError.CorruptHeader` /
   `AudioMetadataError.TruncatedStream`), and chapter-merging conventions.
   Override `supports: Set<AudioFormat>` with a non-empty set — the
   `EmbeddedMetaTypesInCommonMainRule` Konsist rule pins it.
3. **Registration + tests.**
   - Register the parser in
     [`EmbeddedmetaModule`](EmbeddedmetaModule.kt) by adding `singleOf(::<Format>Parser)`
     and including it in the `single<List<AudioFormatParser>>` list.
   - Add a synthetic fixture builder under `server/src/jvmTest/.../fixtures/`
     (`build<Format>File { ... }` DSL) — see
     [`BuildMp3File.kt`](../../../../../test/kotlin/com/calypsan/listenup/server/embeddedmeta/fixtures/BuildMp3File.kt)
     and `BuildMp4File.kt` for the shape.
   - Add Kotest property tests asserting round-trip on randomised inputs
     under `server/src/jvmTest/.../format/<name>/`.

The entry point ([`EmbeddedMetadataParser`](EmbeddedMetadataParser.kt))
and the registry dispatcher do **not** change. The scan summary's
`unsupportedFormats` count drops by the file count of the
newly-supported format on the next scan.

## Validating against a real corpus

Spec §10.4 Definition of Done for a new parser is "no crashes against a
real corpus." The env-gated harness at
[`live/LiveCorpusValidationTest`](../../../../../test/kotlin/com/calypsan/listenup/server/embeddedmeta/live/LiveCorpusValidationTest.kt)
walks an audio directory and reports per-format counts plus crashes /
typed errors:

```
export LISTENUP_EMBEDDEDMETA_LIVE_DIR=/path/to/your/corpus
./gradlew :server:jvmTest --tests "com.calypsan.listenup.server.embeddedmeta.live.LiveCorpusValidationTest"
```

The test is skipped when the env var isn't set, so it never runs in CI.
