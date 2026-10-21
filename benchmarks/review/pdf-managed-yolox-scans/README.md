# Paired managed detector scan check

This JVM evaluation retains all nine previously selected cTDaR samples and all
20 published table annotations. It compares explicit TATR and YOLOX detection
with the same structure graph, original source bytes, 150-DPI rendering,
English Tesseract runtime, orientation decisions and topology scorer.
The fixed profiles and source identities are in `manifest.json`.

| Measurement | TATR | YOLOX |
| --- | ---: | ---: |
| Input cases | 9 | 9 |
| Published table denominator | 20 | 20 |
| Emitted candidate tables | 12 | 14 |
| One-to-one spatial matches, IoU >= 0.5 | 11 | 12 |
| Matched tables with equal dimensions | 9 | 9 |
| Equal dimensions and published unique anchor sets | 3 | 2 |
| Cases ending in a typed OCR limit | 1 | 1 |

YOLOX gains spatial coverage but loses the historical `cTDaR_s002` topology
positive. These results do not justify changing the default detector or choosing
a detector per source. Neither profile provides broad historical-table fidelity.
Anchor agreement is not transcription accuracy: these annotations include
duplicate or incomplete cell labels, and this study has no OCR text oracle.
The existing `OCR_LIMIT` case remains in the full reference denominator.

`CompareManagedScanTables.java` uses maximum-cardinality one-to-one spatial
association. Edges require IoU >= 0.5, traversed by descending overlap and stable
reference index; predictions retain source order. Cell dimensions and anchors
never influence association. Six JVM controls check duplicate-credit prevention,
augmenting paths, exact/below threshold, stable ties and empty predictions.
This metric is separate from older highest-overlap per-prediction summaries.

The TATR report's complete `results` tree equals the frozen historical report.
Initial evaluations preceded formatting; both profiles were rerun with the final
formatted harness without changing models, settings, annotations or scoring.
The manifest retains initial evaluated source hashes as well as final replay
hashes. All 18 original source files are validated by length, SHA256 and Git blob
identity by the existing corpus harness; source bytes are not redistributed.

## Reproduction

Use the existing `PdfPhysicalScanCorpusTest` opt-in invocation documented in
`../pdf-physical-scans/README.md`, with `ALL_PUBLISHED_SAMPLES`,
`PROVIDER_LAYOUT`, 150 DPI and orientation enabled. Supply the exact local
detector/structure/OCR identities in this manifest. For the alternative, add
`-Ddocweave.pdf.scans.detectionFamily=YOLOX` and its local detection graph/hash.
The default family remains TATR. YOLOX requires explicit detector identity and
provider mode; unsupported families and incomplete configurations fail early.
Use a fresh report path for each invocation. Nothing downloads automatically.

Compile `benchmarks/tools/CompareManagedScanTables.java` with Java 21 and the
validation module test classpath. Run its `--self-test`, then run it from the
repository root with the study manifest, TATR report, YOLOX report and a new
summary path. The complete paired reports and summary accompany this README.
Two owned JVM tests verify default TATR execution and explicit family dispatch,
schema rejection, absent identities and unsupported configuration combinations.

Full offline Java 21 `mvn verify` passes 1,151 tests with zero failures, errors
or skips, including strict public Javadocs. Strict private Javadocs pass for the
model-selection helper, its new tests and the standalone comparison tool.
The pinned formatter and `git diff --check` pass. Java 25 remains deferred.
