# Fixed vertical-window detection experiment

All9 pinned cTDaR JPEGs were evaluated on actual Java21 and25. The detector uses either the
whole150DPI-rendered page or each of two fixed windows: upper65% and lower65% of page height,
rounded upward, giving approximately30% overlap. Window placement uses image dimensions only.
No annotation, case-specific crop, source repair, threshold change or model download is used.

The same detector/structure graphs, max800 bilinear normalization, class threshold0.5,
KEEP_HIGHER_SCORE structure policy and original per-call limits remain fixed. The oversized
source stays OCR_LIMIT before window processing. Other sources run three independent profiles,
each with a fresh30-second deadline. This is diagnostic capture, not a production transaction
with aggregate admissions, concurrency, slot/pixel/work limits or managed lifecycle hardened
across windows. All original source/annotation hashes are checked by the comparator.

| Diagnostic | Whole page | Upper window | Lower window | Unfused tile pool |
| --- | ---: | ---: | ---: | ---: |
| Complete structure candidates |12 |4 |9 |13 |
| Exact dimensions and positional/span anchor sets at highest positive envelope overlap |3 |2 |0 |2 |

All9 inputs remain in the report, including one whole-source OCR_LIMIT. The original three
whole-page topology matches reproduce (s0022x5 and s10217x4/5x4). The tile pool loses the
s1025x4 match and adds no new exact topology. These are raw model structures, without OCR,
rule-preferred composition, caption removal or text transcription. Counts are not PDF accuracy.
The union can contain duplicate or partial tables; it is not a fused application output.

On retained historical s003, whole-page detection reproduces raw score0.513383 and one
cross-table region. The upper window emits an artificial-edge-contact detection, but neither
window yields a complete table structure; the lower window has no detection. The original
region-selection problem remains unresolved.

There is a localized positive on modern s104: one published table with zero whole-page
proposal overlap now has windowed structure-envelope IoU about0.94, matching dimensions5x5
and all24 published anchor keys, plus one additional blank model anchor. That is not strict
anchor-set equality, and missing blank-cell annotations must not be silently repaired.
Other regions are lost or shifted. This does not justify replacing whole-page detection or
promoting an annotation-driven window chooser. A future augmentation/fusion policy would
need independent false-positive, duplicate/ownership and aggregate resource/lifecycle checks.

**Decision: reject as a replacement; no production/default change or new beta.** The signed
beta2 candidate remains unchanged and available for Portal validation. This experiment
closes no broad PDF-completion gate. No scan/XML originals, derivatives, weights or text
transcriptions are redistributed; saved files contain numeric predictions and source identities.

## Geometry and validation

Every106-file numeric capture is byte-identical across the two actual JDKs. Helpers compile
with release21 and pass strict standalone Javadocs under both. Manifest records source/graph,
class-profile and capture identities.15 ordinary benchmark-tool tests pass, including three
new comparator boundaries. Production Java is unchanged; the existing905-test baseline is
retained, not claimed as a new local full Maven run for these benchmark additions.

Original JPEG dimensions and the encoded PDF media extent/DPI are recorded. Annotation
comparison maps rendered coordinates through that physical extent; a one-pixel raster-canvas
floor clips an edge rather than resizing the whole JPEG. It must not be treated as uniform
source rescaling. Association uses highest envelope IoU, never best matching span labels;
zero-overlap tables cannot count as matches. Artificial-edge contact is recorded, not silently
filtered. No rule searches the annotations to pick a window or change a prediction.

The first capture revision omitted original/PDF dimensions. Subsequent explicit-frame captures
were rerun rather than rewriting the prior files; only the final capture revision is included
here. Pilot data remains local and is not counted as an additional benchmark success.

## Reproduction

Compile the main modules and assemble an explicit runtime classpath, for example:

```sh
mvn -B -ntp -pl docweave-layout-onnx -am install \
    org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath \
    -DincludeScope=runtime -Dmdep.outputFile=target/runtime-classpath.txt -DskipTests
export DOCWEAVE_BENCHMARK_CP="$PWD/docweave-core/target/classes:$PWD/docweave-pdf/target/classes:$PWD/docweave-layout-onnx/target/classes:$(cat docweave-layout-onnx/target/runtime-classpath.txt)"
```

Supply installed original JPEG/XML cache, pinned graphs and actual JDK homes yourself; nothing
is fetched automatically. Create a9-row filename/TAB/SHA256 pins TSV from the JPEG entries in
`../pdf-physical-scans/extended-manifest.json`. For each JDK, compile the helper into a fresh
class directory, then run with a NEW output directory:

```sh
"$JAVA_HOME/bin/javac" --release 21 -cp "$DOCWEAVE_BENCHMARK_CP" \
    -d "$CAPTURE_CLASSES" benchmarks/tools/CaptureTiledTableRegions.java
"$JAVA_HOME/bin/java" -Djava.awt.headless=true \
    -cp "$CAPTURE_CLASSES:$DOCWEAVE_BENCHMARK_CP" dev.docweave.layout.CaptureTiledTableRegions \
    "$SCAN_CACHE" "$JPEG_PINS_TSV" "$NEW_OUTPUT" \
    "$DETECTOR_GRAPH" "$DETECTOR_SHA256" "$STRUCTURE_GRAPH" "$STRUCTURE_SHA256"
python3 benchmarks/tools/compare_tiled_regions.py \
    --capture21 "$OUTPUT21" --capture25 "$OUTPUT25" --cache "$SCAN_CACHE" \
    --manifest benchmarks/review/pdf-physical-scans/extended-manifest.json \
    --output "$NEW_SUMMARY_JSON"
```

Graph identities and exact fixed settings are in manifest.json. The comparator checks all
image AND XML pins and refuses overwritten output or different paired numeric captures.
