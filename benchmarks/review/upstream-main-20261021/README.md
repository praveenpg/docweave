# Official 0.27.32 release and subsequent main review

The explicit Java freshness guard detected official release 0.27.32 and main
`9a376447d236784a044a4929464179a0164f3329`. [Observed drift](observed-drift.json)
preserves the failed guard rather than treating it as a parser regression. The release
points to the previously reviewed `07d978b8` main checkpoint; its six commits since
0.27.30 are now released. Four later main commits require separate treatment:

| Commit | Change | DocWeave disposition |
| --- | --- | --- |
| cb82b550 | Omit repeated original-element payloads for actually split singleton sources over 1,000,000 text/HTML/image characters | Adopted with explicit JVM omission metadata; see [owned evidence](../chunk-split-source-provenance/README.md) |
| 97edfcb5 | Require newer Python certifi trust data | No JVM certifi dependency; this review does not update or relabel frozen Python execution environments |
| 2f14175f | Release consumed Python decode buffers earlier | JVM's bounded JSON codec and 16 MiB decompressed limit remain distinct; no new decoder-memory parity claim |
| 9a376447 | Release figure pages/crops/JPEG buffers and use bounded upload copies | JVM source-image PNG extraction already uses finally cleanup and cumulative encoded limits; source-image payloads differ from rendered JPEG figure crops; no total heap or compositing parity claim |

The 0.27.33 and 0.27.34-dev1 changelog headings in main are not substituted for official
release metadata. [Review](review.json) records all four commits, changed paths, official
response hashes and exact release/main identities. The complete comparison retains all
18 commits beyond the historical reference, without truncated file coverage.

Historical reference release 0.27.27, its seven golden datasets and the actual measured
0.27.28 PDF comparisons remain unchanged. No new Python runtime comparison, dependency
installation, model execution or automatic golden rewrite is implied by source review.

Official sources: [release](https://github.com/Unstructured-IO/unstructured/releases/tag/0.27.32),
[incremental main](https://github.com/Unstructured-IO/unstructured/compare/07d978b8c182404b800fba27c01f4a2c633cfffc...9a376447d236784a044a4929464179a0164f3329).
