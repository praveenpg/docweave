# Native text on frozen model geometry: feasibility probe

Offline projection of original native glyphs onto the unchanged model cell geometry matches
**59/59** issuer HTML rows exactly: operations27/27 and balance-sheet32/32. On these same
pages, model PAGE OCR matched42/59 and CELL OCR44/59. All six existing native table modes
emitted no Tables. This supports implementing a native-text/model-layout integration next.
It is not an implemented production capability, a general accuracy score or a beta milestone.

## Scope and fixed protocol

The [original sources and references](../pdf-current-business-rows/manifest.json) and
[model geometry](../pdf-current-business-model/README.md) remain unchanged. Before projection,
`manifest.json` froze half-open glyph-center ownership, existing `PdfGlyphLines` spacing and
baseline grouping within each anchor, space joining of wrapped lines, original reference
normalization and exact ordinal row scoring. No reference text/ROI enters projection. No
geometry, threshold, text or source-label tuning was performed after the probe result.

`ProbeNativeModelText.java` reads the original PDF and the previously captured PAGE table
geometry. It verifies both byte identities and matching upright origin-zero unit crop/frame,
retains saved row/span topology, clears every OCR cell string, then fills each anchor using
native glyphs. Source glyph centers use x+width/2 and baseline-height/2. Cell rectangles are
half-open, and a repeated source glyph claim fails. Numeric/currency/case/punctuation text
is never repaired. Existing native line spacing handles separated currency symbols and digits;
the evaluator still preserves currency spacing exactly. Blank anchors stay blank.

This probe has **zero new OCR or model calls**. It reuses geometry from a prior model run;
model inference remains necessary in any future live integration. It does not test latency,
whole-document provider invocation, scan fallback, native/OCR arbitration or production table
emission. It reports claimed/unclaimed glyph counts but does not emit ordinary unclaimed
text/images. Saved topology was validated by the earlier production OCR capture; this probe
is not a replacement general topology validator. Unsupported transformed native text fails.
No public service API or production dependency/default changes.

## Paired Java21/25 results

| Case | Native-probe exact rows | PAGE exact rows | CELL exact rows | Parent IoU |
| --- | --- | --- | --- | --- |
| operations |27/27 |18/27 |20/27 |0.972067 |
| balance-sheet |32/32 |24/32 |24/32 |0.981204 |

Both tables retain their exact expected nonblank row counts; no missing/extra row positions.
All59 reference rows remain in each profile denominator. Equal row text does not independently
validate physical cells/spans: issuer HTML presentation cells are still content labels only.
One issuer and two development tables do not establish held-out generalization. Native glyph
quality, model errors, mixed pages, rotation and overlapping ownership require production
regressions before an application-facing API is added.

## Reproduction

Compile the launcher with `javac --release21`, current module classes and development
PDFBox/Jackson/Jsoup dependencies, then run:

```sh
java -cp "$classes:$classpath" dev.docweave.partition.ProbeNativeModelText --self-test
java -cp "$classes:$classpath" dev.docweave.partition.ProbeNativeModelText \
  /path/to/source-cache benchmarks/review/pdf-current-business-model/cases.tsv \
  /path/to/original-model-captures /path/to/NEW-native-probe-captures
```

Original capture hashes must match the committed model experiment identities for that case
on Java21 or25. Raw captures contain third-party text/HTML and remain local/fetch-only.
Score them using the existing Java evaluator with the explicit experimental flag:

```sh
mvn -B -ntp -pl docweave-validation -am test \
  -Dtest=PdfCurrentBusinessRowsTest,StandaloneSourceCompatibilityTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Ddocweave.pdf.current.business.cache=/path/to/source-cache \
  -Ddocweave.pdf.current.business.report=/path/to/NEW-native-control.json \
  -Ddocweave.pdf.current.business.model.captures=/path/to/native-probe-captures \
  -Ddocweave.pdf.current.business.model.report=/path/to/NEW-probe-score.json \
  -Ddocweave.pdf.current.business.model.native.probe=true
```

The ordinary build neither downloads documents nor executes OCR/models. The standalone-source
compatibility test compiles the probe for release21 and runs owned offline controls for wrapped
and section text, preserved parenthesized numbers, empty cells, half-open boundary ownership
and overlap rejection. Full clean verify passes928 tests and strict Javadocs on actual21/25,
plus36 offline tooling guards and pinned formatting. Text-free paired reports and identities
are committed here; raw text and downloaded sources remain outside Git.

Next implement an explicit native-text/layout strategy with ownership, output/resource and
mixed-document regressions, then repeat this fixed experiment before adding more sources.
Overall PDF feature completion remains roughly85–90% as a planning estimate, not accuracy.
