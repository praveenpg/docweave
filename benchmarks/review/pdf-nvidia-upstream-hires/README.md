# Whole-source NVIDIA: actual upstream hi_res comparison

The same original eleven-page NVIDIA FY26Q4 release is processed by pinned
Unstructured 0.27.28/inference 1.6.13, using the existing public `partition_pdf`
runner, `hi_res`, YOLOX, 300DPI, English Tesseract and the upstream default
PubTables structure checkpoint. All local assets are verified before execution;
no new dependencies or downloads. This profile differs from DocWeave's explicit
150DPI/v1.1-All configurations. This is not a shared-weights or default-vs-default
comparison, a new extraction improvement, or a general library ranking.

The manifest was frozen before extraction. The whole document completed within
its 300-second limit: 241 raw elements, including fourteen Table elements. No
reference pages, crops, row labels or source-specific thresholds enter extraction.
The unchanged source/reference manifest retains all six fixed regions and all
110 publisher HTML rows, including titles, date/year rows and original punctuation
spacing. HTML references do not establish physical PDF cell/span topology.

| Metric | DocWeave v1.1-All / TATR | DocWeave v1.1-All / YOLOX | Unstructured default table / YOLOX |
| --- | ---: | ---: | ---: |
| Associated original regions | 3/6 | 6/6 | 6/6 |
| Emitted associated content rows | 66 | 90 | 84 |
| Exact original ordinal rows | 0/110 | 0/110 | 0/110 |
| Exact complete-row occurrence presence | 39/110 | 66/110 | 42/110 |

The first two columns retain the existing
[complete frozen structure replay](../pdf-tatr-fin-structure/README.md).
Presence consumes each observed occurrence at most once; it does not shift or
repair the strict ordinal score. Unstructured's statement-level presence is
10/28 income, 20/33 balance sheet, 12/49 cash flows; emitted rows are 21, 22, 41.
Finding all six table regions does not establish full statement fidelity.

`ScoreNvidiaUpstreamRows.java` uses the existing `UpstreamTableCapture` JVM adapter
and invokes the exact 8c4e990/f88af667 frozen multi-page scorer, including its
geometry-only IoU>=0.5 stable-first association and original row normalization.
It checks source PDF/HTML, reference, executed manifest, capture runner and runtime
identities, then derives the additional occurrence-presence diagnostic from the
same associated rows. It must run with the historical scorer classes first from
the isolated frozen workspace; do not overwrite or relax its source-hash guard.

Adaptation includes Table elements only. Consequently `document_elements`,
`selected_page_elements` and `page_text_nonblank` in the frozen helper's output
refer to that adapter representation, not the raw upstream document. In particular,
false `page_text_nonblank` is not evidence that upstream failed to read page text.
`raw_document_elements` records the separate actual count; whole-capture failure
remains a failure with every source reference retained.

Validation: five Java21 methods pass (four interim adapter/scoring controls and
the standalone-source compiler check), standalone frozen-classpath compilation,
strict private Javadocs and scoped pinned formatting pass. Additional JVM replay
controls verify timeout retains all 110 reference rows with zero associations,
and changed raw-source/manifest identities are rejected without a report. The
latest full production check remains 1,224 Java21 tests; no repeated full-reactor
or Java25 run was needed for this evaluation-only tool. No release, public visibility or production default change.

Raw source/capture stay outside Git. `runtime.json` pins packages/runner/manifest;
`java21.json` pins the complete raw capture digest and numeric results. Reproduce
with the committed capture runner and manifest, the original NVIDIA source cache
and preinstalled upstream runtime assets. Set the pinned Tesseract PATH/tessdata,
HF_HOME to the populated assets cache, offline flags, native library path,
OMP_NUM_THREADS=4 and OMP_THREAD_LIMIT=1 as in the earlier interim protocol. Use a
fresh output directory. Compile the JVM tool with Java21 and run it from the
frozen scorer workspace with arguments: this manifest, original source directory,
raw upstream directory and a fresh report. No model binary or raw source text is
redistributed here.

Next controlled question: compare the already-preinstalled v1.1-All source weights
inside the upstream pipeline, retaining this default-model result separately.
Neither result licenses source-specific model selection or reference repair.
