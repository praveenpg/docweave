# Additive PDF strategy presets

`PdfPartitioner.builder()` maps FAST/AUTO/HI_RES/OCR_ONLY onto the bounded existing PDF services.
The public service/builder contracts are interfaces; implementations stay hidden and enums are
immutable values. Existing factories, ServiceLoader native defaults and optional dependency sets
are unchanged. Read the [full routing contract](../../../docs/pdf-support.md#convenience-strategy-presets-unreleased)
before assuming similarly named upstream strategies have identical semantics.

FAST never invokes OCR/layout. AUTO uses native text when present and optionally recognizes
textless pages, including blanks. OCR_ONLY recognizes every rendered page, replacing native
text/tables while retaining source images. HI_RES does the same with actual-word OCR and explicit
provider table topology, defaulting to300DPI instead of150. Explicit OCR options override DPI;
presets own selection and HI_RES table policy. No language guessing, downloads, classpath model
discovery, confidence heuristic, semantic figure classification, retries or failure fallback.

Low-level Selection.ALL_PAGES is independently available, including existing oriented and
cell-refinement factories. Full-page OCR bypasses native glyph grouping/search layers; graphics,
permissions, input/page, source-image, rendering and raw provider-output limits remain. Source
images emit once, and recognized elements carry pdf_ocr_selection=all_pages_v1. Existing
TEXTLESS_PAGES/IMAGE_REGIONS behavior and constructors stay unchanged; exhaustive enum switches
must handle the additive selection. withDpi(int) copies every other immutable option.

## Owned validation

Final clean verification passes1,053 tests on each actual Java21/25 with zero failures/errors/
skips, strict public and touched-private docs, pinned formatting and diff checks. The explicit
real-engine controls also pass separately on both JDKs. Exact versions/log/asset hashes and
the distinction between ordinary versus installed-engine checks are in verification.json.

PdfStrategyTest supplies four original owned pages: native-only, raster-only, mixed native/raster
and blank. Sixteen controls cover native/default equality, routing by page, original image counts,
absence of native/OCR duplicates, actual-word table content, default/override raster dimensions,
missing/orphan/incompatible providers, nulls, builder snapshots, render/raw-output limits, engine/
layout failure causes, interruption, empty output, quarter turns and virtual-thread reuse.
Elapsed duration is excluded explicitly from extraction equality; all deterministic output,
warnings, input identity and format fields remain compared. No source/reference labels are repaired.

TesseractStrategyInstalledTest runs only with explicit executable/tessdata/fresh-report properties.
An owned native PDF has the independently known text `Invoice total 12345`.
All four presets retain exact text once. Counters prove FAST/AUTO make
no engine calls on that native page; OCR_ONLY invokes real OCR, and HI_RES invokes real word OCR
plus the explicitly supplied empty-layout strategy. Empty layout retains ordinary OCR text.
The word path does not manufacture an image/table detection. This validates engine integration,
not corpus accuracy or real-model table quality; existing owned model/native tests remain.

The installed21/25 XML receipts record exact engine/English-model hashes and numeric calls.
No original third-party documents, recognized publisher text, binaries or operational paths are
added. The existing interim corpus comparison remains pinned; this feature is not presented as
a refreshed measurement of those development documents.

## Reproduction

Run clean mvn verify under actual JDK21 and25 with the configured dependency cache. Ordinary
verification invokes no installed OCR engine. For explicit real-engine integration run:

```sh
mvn -B -ntp -o -Dmaven.repo.local=/tmp/docweave-m2 \
  -pl docweave-ocr-tesseract -am test \
  -Dtest=PdfStrategyTest,TesseractStrategyInstalledTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Ddocweave.tesseract.executable="$TESSERACT_EXECUTABLE" \
  -Ddocweave.tesseract.tessdata="$TESSDATA_DIRECTORY" \
  -Ddocweave.tesseract.report="$NEW_REPORT_PREFIX"
```

Configure the executable's native library path where necessary; no downloads occur. The test
creates NEW_REPORT_PREFIX.strategies.xml with CREATE_NEW and rejects missing configuration.
Run pinned formatting plus strict private doclint on touched production/test classes on both
JDKs; public strict docs are part of verify. Java21 is the baseline with no preview APIs.

The fresh opt-in JVM upstream check passes against reviewed0.27.29/mainf9860223; the exact
official response identities are in upstream-status.json. Historical reference goldens and
measured0.27.28 comparison captures stay pinned. This addition is unreleased; no version, tag,
GitHub release or Central publication is performed as part of implementation verification.
