# Authored header-span regression baseline

Eight synthetic sources define intended cell spans before extraction:
ruled/borderless layouts crossed with wrapped headers, true stacked headers,
grouped headers, and wrapped body text. Each includes six complete body rows.
Grouped headers include both a row-span of two and a column-span of three.
Authoring and expected spans never consult model predictions or the education
corpus labels. These are owned synthetic controls, not externally annotated,
human-reviewed real-world headers or a held-out population accuracy estimate.

## Results

Actual Java21 and25 parsed reports agree. All eight original single-page documents
complete successfully with exactly one emitted table each. Full exact span AND
cell-content matches: **6/8 cases**. Four ruled cases use native grids; four
borderless cases use the pinned detector/structure models. Borderless stacked
and grouped cases pass. Borderless wrapped and body-wrapped cases fail: the
wrapped header becomes two logical rows rather than one, shifting all body rows.
Both fail exact content/position checks for all28 expected anchors, even though
the text is present. They remain in the denominator. This is not zero document
text extraction or an overall PDF accuracy figure.

The topology-only header field matches8/8 but is insufficient: a four-cell first
row can match the expected indices/spans while owning only the first physical
line of a wrapped label. `exact_content_and_topology` is the decisive case gate.
No row shifts, fuzzy content matching, empty-row deletion or reference-fed
inference are allowed. Body and spanning-cell negatives remain visible.

Exploration is explicit: the initial topology-only run prompted addition of exact
cell-content checks from the same authored definitions. Its original manifest,
references, case pins and Java21 report are retained with `exploratory-` prefixes.
An intermediate generator omission caused content scoring to reject missing
content_keys without a report. A generation/serialization self-test now guards
that contract. Final references and manifest were frozen before final runs on
both JDKs; this is development evidence, not blind evaluation. Final-helper.json
records an import-order-only formatting change after freezing.

## Reproduction

Build the reactor and provide its runtime classpath (including PDFBox, jsoup,
Jackson and optional ONNX). Compile the standalone helpers at release21:

```sh
javac --release 21 -cp "$classpath" -d "$classes" \
  benchmarks/tools/HeaderSpanEvaluation.java benchmarks/tools/CaptureNativeModelTables.java
java -cp "$classes:$classpath" dev.docweave.partition.HeaderSpanEvaluation generate "$new_sources"
java -Djava.awt.headless=true -cp "$classes:$classpath" \
  dev.docweave.partition.CaptureNativeModelTables \
  "$new_sources" "$new_sources/cases.tsv" "$new_captures" \
  "$detector" 0f625baa8849eff26e265ce08a2f6b1479e73f52edd5adc09799c7e7fcc689b9 \
  "$structure" ff1d19a18cb5f041c1692ff681f891a5180f9430d68a71d2cf7a0a2e8e55b990 native-first
java -cp "$classes:$classpath" dev.docweave.partition.HeaderSpanEvaluation score \
  "$new_sources" "$new_captures" "$new_report"
```

Capture uses native-first PARTIAL_TEXT150DPI/16Mpixels/1024anchors/fourthreads and
KEEP_HIGHER_SCORE, unchanged from the previous baseline. It reads only the source
filename/page/hash pins, never span/content references. Missing or multiple emitted
tables fail the full case; missing/malformed capture files stop scoring instead of
silently dropping a case. Expected text collapses explicit authored lines to ASCII
spaces; captured cell text collapses ASCII whitespace only. No external source
text is redistributed. PDF binaries and raw captures stay outside Git. Generated
PDF metadata/identifiers can change byte hashes between generations; freeze the
new generated identities before each run, rather than substituting old hashes.

No production extraction changes or beta release accompany this regression set.
Before accepting a repair, preserve all eight controls and the untouched real-world
corpus, and add independently annotated real-world header spans. The synthetic
result does not resolve ambiguity in genuine source documents or close broader
business/scientific, historical/degraded-scan or final comparison gates.
