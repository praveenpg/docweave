# Business-page cell-text coverage gate

This is a negative coverage result for the explicit **COMPACT dark-grid-only** strategy,
not an OCR accuracy score or a regression against a previous business-page baseline.
The fixed selection contains two business PDFs (financial disclosure and transport
procurement) and one separately labeled public education-statistics control. These are
2012/2016-era and statistical documents, not a representative sample of current business
and scientific PDFs. No scientific case is included yet.

## Selection and fixed profiles

`manifest.json` pins six PDF/CSV inputs to Tabula commit
`2cdf3b4fd3f7e921dca8cc6814cdd9316be40f0f`, including immutable URLs, SHA256,
Git blob SHA1 and byte sizes. The source test hashes and published page/rectangle selections
are recorded. Selection and the uniform policy were saved before recognition; no case was
chosen from its OCR result. The reference regions come from the pinned
[TestWriters](https://github.com/tabulapdf/tabula-java/blob/2cdf3b4fd3f7e921dca8cc6814cdd9316be40f0f/src/test/java/technology/tabula/TestWriters.java),
[TestSpreadsheetExtractor](https://github.com/tabulapdf/tabula-java/blob/2cdf3b4fd3f7e921dca8cc6814cdd9316be40f0f/src/test/java/technology/tabula/TestSpreadsheetExtractor.java)
and [TestBasicExtractor](https://github.com/tabulapdf/tabula-java/blob/2cdf3b4fd3f7e921dca8cc6814cdd9316be40f0f/src/test/java/technology/tabula/TestBasicExtractor.java).

References are upstream saved **native extraction CSV expectations**, not human-reviewed
scan transcriptions or complete physical span labels. Each selected complete page is rendered
at150DPI, embedded losslessly at its original displayed physical extent into an image-only
PDF, then processed through the actual PDF pipeline at150DPI. There is a second rasterization;
rounding/resampling is part of this diagnostic. Native text is deliberately absent from the
derivative so the explicit textless-page OCR policy executes. No annotation rectangle crops
or repairs enter inference. The procurement source declares rotation90; the renderer applies
it and the derivative preserves the displayed792x612 frame with rotation0. Original raster
sources are printed native PDFs, not physical scans.

Both profiles use the same real Tesseract5.5.3/Leptonica1.87.0 installation, pinned English
model, page AUTO/PSM3 and cell SINGLE_BLOCK/PSM6, retained borders, no model layout,
no orientation estimate, no magnification, zero cell context, COMPACT rule-only layout,
16million render pixels,10,000 aggregate lines and1million UTF-16 units per page.
Max regions is64 (unused for TEXTLESS_PAGES). `maxTableCells=1024` is an explicit uniform
caller policy declared before recognition: two independent CSV grids exceed the default256.
This is not a result under all default budgets. Other table/process/output budgets remain
the production defaults. The only comparison switch is PROVIDER_LAYOUT versus
PROVIDER_LAYOUT_CELLS with an independently supplied CELL engine interface.

## Results on actual Java21 and25

| Case | Domain | Reference grid | PAGE tables | CELL tables | Matched structured CSV positions, either profile |
| --- | --- | --- | --- | --- | --- |
| financial-disclosure | Business |88x5 |0 |0 |0/440 |
| transport-procurement | Business |14x11 |0 |0 |0/154 |
| education-statistics | Public-statistics control |50x7 |0 |0 |0/350 |

All six runs per JDK complete successfully, with one PAGE recognition and one layout call
per case/profile. The layout provider returns zero tables each time, and **zero cell OCR
calls execute**. Consequently this experiment cannot rank PAGE versus CELL text recognition.
The business-only structured coverage is0/594 positions; including the explicitly separate
statistics control it is0/944. Neither means Tesseract recognized zero words. No prose/word
transcription accuracy was measured. The implementation's general PDF quality remains open.

The next useful experiment is the same frozen cases and references with an explicit model
layout provider, keeping this rule-only result as a control. It must record its own model,
fixed policy and costs; it must not use CSV text/ROIs to select or repair predictions.
No defaults or production algorithms change here, and this evidence-only addition does not
justify a new beta release. The previously prepared beta.3 candidate remains unchanged.

`java21.json` and `java25.json` retain text-free counts, dimensions, geometry, mismatch-index
hashes, actual PAGE raster hashes and local capture hashes. All per-case records agree across
JDKs; Java runtime identities differ. Source/executable/model/tool identities are recorded in
`identities.json`. Source PDFs, CSVs, raster derivatives, recognized text and HTML are fetch-only
or local temporary data and are not redistributed in this directory.

## Reproduction and interpretation

Fetch the six immutable inputs yourself into a local cache and verify the manifest identities.
Install the selected engine/model separately. With a Java21+ runtime classpath containing
current core/PDF/Tesseract/ONNX classes, PDFBox dependencies and Jackson3 (the development validation
classpath supplies them), compile the optional launcher. The ONNX classes are now needed to
compile the launcher's optional model branch; the original five-argument control neither
loads a model nor invokes ONNX inference. Use a **new** output directory per run:

```sh
javac --release 21 -cp "$DOCWEAVE_CLASSPATH" -d /tmp/business-classes \
  benchmarks/tools/CaptureBusinessCellText.java
java -Xmx1g -Djava.awt.headless=true -cp "/tmp/business-classes:$DOCWEAVE_CLASSPATH" \
  dev.docweave.partition.CaptureBusinessCellText \
  /path/to/cache benchmarks/review/pdf-business-cell-text/cases.tsv \
  /tmp/business-new /path/to/tesseract /path/to/tessdata
java -cp "docweave-validation/target/test-classes:$DOCWEAVE_CLASSPATH" \
  dev.docweave.validation.ScoreBenchmarkCaptures business-cell-text \
  benchmarks/review/pdf-business-cell-text/manifest.json /path/to/cache \
  /tmp/business-new /tmp/business-new-summary.json
```

The comparator was migrated to the JVM on 2026-10-22 (`ScoreBenchmarkCaptures business-cell-text`, run from the
repository root after `mvn test-compile`); it reproduces this directory's comparison semantics; byte parity with the retired script was shown on fresh native captures.
Identity files still pin the retired Python source, which remains in repository history. See the
[migration evidence](../python-scorer-migration-2026-10-22/README.md).

The launcher reads only PDF identities and selected page numbers from `cases.tsv`; it never
reads CSV labels or reference regions. The comparator verifies all six input identities.
It associates at most one emitted table by maximum parent-box IoU>=0.5 with the published
reference rectangle, never by best text. Unassociated outputs are reported separately;
partial annotation coverage does not make them proven false positives.

For a matched table, emitted row-major HTML anchors pair with emitted cell metadata. Merged
anchor text is projected only at its origin, with empty covered evaluation slots. This does
not alter output or establish strict span accuracy. Missing/uncovered slots cannot earn
credit for expected empty cells. Text comparison collapses ASCII whitespace only; case,
punctuation and non-ASCII spacing remain. All944 reference positions remain in each
profile's denominator, including failures and missing tables. Exact nonempty/empty counts,
dimension agreement and whole-grid text agreement remain separate.

Six offline comparator guards cover missing-empty credit, uncovered slots, merged projection,
geometry-only association, whitespace policy and escaped HTML/overlapping-anchor validation.
They run no engine and fetch nothing. The Java launcher compiles for release21 and passes
strict Javadocs on actual21/25; the reactor standalone-source guard also compiles it with the
other optional helpers. Intermediate capture attempts are not accepted evidence: an initial
launcher rejected declared rotation and a following attempt embedded the rotated raster with
incorrect unrotated dimensions. Accepted captures were rerun after correcting the displayed
frame and adding stage counters/pixel fingerprints; those earlier files remain only in /tmp.
