# Native multiline cell representation

Java 21 development evidence for the explicit `PRESERVE_LINE_BREAKS` policy. Input
identities were frozen before the original-source comparison in input-identities.sha256.
The unchanged original NVIDIA PDF and both complete model-output captures are pinned
by ../pdf-nvidia-proposal-structures/replay-manifest.json. All 11 original pages run
once per policy and detector profile. No source ROI, reference text, row repair,
threshold change or best-of selection enters extraction.

| Fixed model capture | Tables in full document | Tables with retained breaks | Retained breaks |
| --- | ---: | ---: | ---: |
| TATR detection/structure | 8 | 4 | 19 |
| JVM YOLOX detection + TATR structure | 14 | 11 | 36 |

CompareNvidiaCellLines.java checks exact original source/raster hashes and requires
every original page callback. All non-table elements and other table metadata match
exactly. Replacing new LF characters with spaces reproduces the original text;
replacing policy-owned `<br>` with spaces reproduces original HTML. Coordinates,
spans, source ownership and element ordering are preserved. The numeric report is
java21.json; source text, images and models remain outside Git.

This is preservation of geometric source-line evidence within existing cells. It
does not split model rows, attach excluded headings, improve the unchanged NVIDIA
0/110 strict ordinal result or establish broad model accuracy. Both model profiles
are diagnostic cached replays, not a new production detector adapter.

Validation: full Java 21 `mvn verify` passes 1,112 tests with zero failures/errors,
plus strict Javadocs. Three added Java tests cover both provider factories, ordinary
ownership, geometry, HTML escaping, same-baseline runs, tagged spans, rotated fallback
and original cell limits. Strict private standalone Javadocs and compilation pass
for the comparison helper. Java 25 is deferred until PDF functionality completion.
An additional JVM pipeline control checks exact JSON round-tripping, unchanged
skip-table chunking and original-element provenance when structural chunks normalize
cell line breaks to spaces. This normalization is existing chunking behavior.

To reproduce, compile ReplayNativeLayoutCaptures.java and CompareNvidiaCellLines.java
with the built Java 21 reactor/test dependency classpath. Run
`dev.docweave.validation.CompareNvidiaCellLines` with the original source cache,
frozen numeric structure capture root and a fresh report path. Use headless AWT.
Verify input identities with `sha256sum -c` from the repository root before running.
