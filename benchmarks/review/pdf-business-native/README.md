# Native extraction on the frozen business PDF set

The default native extractor matches all440 saved financial-disclosure CSV positions, while
its rasterized/model-OCR alternatives matched49 (PAGE) or39 (CELL). Procurement and the
separate statistics control still have native table-structure gaps. This does not establish
general native superiority or physical-scan recognition quality.

## Fixed protocol

`manifest.json` was saved before extraction and pins the unchanged
[source selection](../pdf-business-cell-text/manifest.json). All five existing public native
table policies run uniformly on all three source PDFs: default ruled-only, aligned,
multiline aligned, double borders and horizontal text. There is no per-case policy selection
or threshold adjustment based on the references. All native limits/options are defaults
except requested cell-coordinate metadata and the explicitly selected table mode. In particular,
native default TableLimits admits1million positions; the prior OCR experiments explicitly used
maxTableCells1024. These are distinct policies, not a claim of identical native/OCR budgets.

The capture tool processes the **complete original pinned PDF bytes**, without page copying,
rendering, OCR or model use. It scores only the previously selected page after extraction,
retaining original page numbers, declared rotation, fonts, content and displayed coordinates.
Sources contain1 financial,5 procurement and6 statistics pages. Any document-level extraction
failure would remain a failure for the selected page, including one arising elsewhere in the
original document; all captures succeed here. Only three selected pages are evaluated, not all
source pages. No reference CSV text or ROI enters extraction.

The comparator retains maximum-parent-IoU>=0.5 association and ASCII-whitespace-only text
normalization. No fragments are joined, columns shifted or covered slots invented to improve
comparison. Missing slots do not receive credit for expected blanks. All944 reference positions
remain in each profile's denominator. Reference CSVs are upstream saved **native extraction
expectations**, not reviewed physical-span labels or physical-scan transcriptions. The fixture
set contains older business documents and one public-statistics control; current business,
scientific and held-out coverage remain open.

## Results on actual Java21 and25

All five native policies produce the same selected-page tables and positional results:

| Case | Reference grid | Native output | Geometry-associated matches | Fully matching CSV text grid |
| --- | --- | --- | --- | --- |
| financial-disclosure |88x5 |One88x5 table,432 anchors |440/440 |Yes |
| transport-procurement |14x11 |Two fragments:14x2 and14x8 |0/154 |No |
| education-statistics (separate control) |50x7 |No Table element |0/350 |No |

The financial result includes266/266 nonempty and174/174 empty positions. Its432 actual
anchors project into440 evaluation positions because of merged cells; no extra cells are
added to extraction output. CSV equality is not an independent proof of complete span topology.
Parent ROI IoU is0.993415. It still reports the documented general
`PDF_EXTRACTION_LIMITATION` capability warning; that is not a detected extraction defect.

Procurement's larger fragment is associated geometrically (IoU0.671653), with14x8 dimensions.
The separate14x2 fragment has IoU0.159375 and remains unmatched. Its repeated row geometry
and the intervening column region require investigation, not automatic joining. Local positional
columns start at each fragment's origin, so0/154 means disagreement with the complete reference
grid, not zero extracted text. The original selected page emits32 elements, including these
two tables. The unmatched fragment is not labeled a false positive or discarded from output.

The statistics page emits334 elements but no structured table. Its text was not independently
scored as prose; absence of a Table does not establish lost document text. `geometry.json`
records selected/document element counts, table dimensions/anchor counts/methods/bounds and
warning codes, without source text or HTML.

Per policy, native totals are440/594 business-only positions and440/944 including statistics.
Those totals must not conceal that only one of the two business reference tables matches.
For context, the fixed model-OCR business totals were114/594 PAGE and127/594 CELL; financial
was49/440 or39/440, procurement65/154 or88/154. Model-OCR statistics was67/350. These are
alternate extraction paths on a small development fixture set, not a universal routing rule
or aggregate PDF accuracy. All native engine/model/rasterization call counts are zero;
`layout_calls=0` means no external provider invocation, not absence of native geometry analysis.

## Reproduction and evidence

Use the pinned original cache and `cases.tsv` from the earlier experiment. With current
core/PDF classes, PDFBox dependencies and Jackson3 on the validation runtime classpath:

```sh
javac --release 21 -cp "$DOCWEAVE_CLASSPATH" -d /tmp/native-business-classes \
  benchmarks/tools/CaptureNativeBusinessTables.java
java -Xmx1g -Djava.awt.headless=true \
  -cp "/tmp/native-business-classes:$DOCWEAVE_CLASSPATH" \
  dev.docweave.partition.CaptureNativeBusinessTables /path/to/cache \
  benchmarks/review/pdf-business-cell-text/cases.tsv /tmp/native-business-new
python3 benchmarks/tools/compare_business_cell_text.py \
  benchmarks/review/pdf-business-cell-text/manifest.json /path/to/cache \
  /tmp/native-business-new /tmp/native-business-summary-new.json \
  --profiles default aligned multiline double-borders horizontal
```

The output directory and summary file must be new. `java21.json`/`java25.json` retain text-free
metrics, source-frame identities and local capture hashes. All15 numeric/geometry records and
the full local decoded capture semantics agree across JDKs. Capture-byte hashes may differ
because map key order is not canonical. `identities.json` pins tool/protocol identities and
records exact replay of all four prior rule/model comparator reports using its unchanged
PAGE/CELL default. Older reports and manifests are not rewritten. Raw PDFs, CSVs, captured
HTML/text and models remain local/fetch-only.

The comparator adds explicit profile names while preserving its existing default. Two new
offline guards check invalid/duplicate profile rejection before file access and a native
profile's complete denominator. All36 tooling guards pass without engines or downloads.
The launcher compiles for release21, passes strict public/private Javadocs, and passes the
reactor standalone-source guard on actual21/25. No production behavior changes here.

Next audit the procurement source strokes and native grid-component handling to identify
why the complete reference table is emitted as two fragments. Keep the exact financial case
and all negative controls as regressions before proposing a fix. Do not automatically bridge
unobserved boundaries or promote a tuned threshold. This evidence-only addition warrants no
new beta; the existing signed local beta.3 candidate is unchanged.
