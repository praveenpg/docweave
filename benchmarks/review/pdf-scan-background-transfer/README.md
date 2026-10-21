# New-page transfer of explicit background normalization

Production127a2f9 was frozen before source fetch/OCR. In each of the two already
selected degraded-scan works, choose the next lexical GT-PAGE XML after the previously
measured page, requiring a same-stem TIFF in the frozen original tree. This selects
1778/0066 and1780/00002 before image or recognition inspection. No source/result-based
exclusions, new parameters or per-case policy selection are allowed. These are previously
unmeasured pages from known works, with unknown training overlap, not broad held-out
accuracy. Original work condition labels do not establish each new page's degradation.

Source files match their original Git blob SHA1 and independently recorded SHA256/lengths.
The unchanged strict JVM XML/parser defines all references and source TIFF DPI. No OCR
output enters preparation. Both sources use300DPI and contain15/26 labelled lines,
224/921 normalized reference code points. Neither declares complete logical line order;
all whole-sequence edit counts below remain XML-sequence diagnostics, not validated CER.
Source pixels/dimensions, reference hashes, models, executable, native thread limit and
AUTO/ENGINE_DEFAULT policies remain the same before/after preparation.

| Complete source/model pair | Associations before→after | Exact associated lines | Missing reference lines | Extra predictions | XML-sequence character edits |
| --- | ---: | ---: | ---: | ---: | ---: |
|1778/0066 German|4→5/15|0→0|11→10|10→8|28→24|
|1778/0066 Fraktur|4→5/15|1→1|11→10|10→8|20→20|
|1780/00002 German|26→26/26|2→2|0→0|0→0|116→112|
|1780/00002 Fraktur|26→26/26|3→3|0→0|0→0|53→56|

All8 OCR calls succeed. Every41-line/1,145-code-point single-model denominator remains;
missing/extra lines and the Fraktur sequence regression are not removed. Local association
and text exactness remain limited, especially on1778. Small coverage gains transfer, but
there is no universal quality improvement, new exact-line gain or semantic-order claim.
The earlier four-page study's1780 association loss and gray-field erasure negative remain
applicable. Defaults stay unchanged; LOCAL_WHITEPOINT is explicit PAGE-only preparation.

## Reproduction

selection.json freezes source eligibility and exact original Git blobs before fetch.
Download the four listed files from the pinned original repository commit; raw source
scans/transcriptions stay outside Git. Compile PrepareScanTransferManifest.java against
the existing Java21 validation/test classpath, then supply selection, source cache and
fresh manifest. It verifies blob SHA1/lengths and reuses the existing strict reference/
DPI parser. The generated manifest pins complete SHA256s and reference counts/text hashes.

Run PdfHistoricalTextCorpusTest#optionalOriginalHistoricalTextUsesCompletePublishedReferences
with OMP_THREAD_LIMIT=1 and explicit cache/report/executable/tessdata/manifest properties,
plus docweave.pdf.historical.source.resolution=true. Run once with unchanged factories;
then run with background.whitepoint=true and background.managed=true under the historical
property prefix. Reports require fresh output paths. Same two models run uniformly on
both complete pages, for four calls per policy. No oracle cropping or reference repair.

CompareBackgroundScans.java accepts baseline/candidate/fresh output plus this frozen
manifest path. It derives the exact expected source/model key set and rejects missing,
extra or duplicate observations. Its historical three-argument mode still requires eight
pairs, and equivalence mode stays unchanged. Re-executing that mode retains every prior
eight-case result exactly; no historical score/report is rewritten. Comparator population
checks changed, not reference parsing, association, text normalization or score formulas.

Java21 compilation, pinned formatting and strict private Javadocs pass. This benchmark-only
follow-up adds no product code after127a2f9's1,185-test full managed verification; no
redundant full reactor/Java25 run is claimed. No release/publication/visibility change.
