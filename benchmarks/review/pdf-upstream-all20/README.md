# Fixed20 upstream comparison

DocWeave has better measured span topology on this scientific-table-crop set under the
configurations evaluated. This establishes a scoped comparison, not an overall library ranking,
general PDF accuracy, commercial-service comparison or completion of the PDF quality gates.

| Profile | Inputs with a nonempty table | Inputs with exactly one complete span topology |
| --- | ---: | ---: |
| DocWeave explicit KEEP_HIGHER_SCORE structure + CELL OCR |20 |14 |
| Historical DocWeave detector + structure/PAGE words |19 |14 |
| Upstream postprocessing of identical DocWeave decoded objects |20 |8 |
| Upstream table recognizer with shared source weights |20 |8 |
| Unstructured public PDF hi_res, pinned default model identities |11 |0 |

All denominators are20; all1380 published anchors remain represented. Complete topology means
the same dimensions and full row/column/span-key set, with exactly one table candidate; it
does not establish correct cell pixel bounds or OCR strings. Every profile has zero wholly
exact structure/text tables under its stated parser/OCR measurement. Aggregate OCR-string
counts are deliberately not used to rank Java/Jsoup versus Python/BeautifulSoup configurations.

The public upstream profile emits14 nonempty HTML tables across11 inputs, including two
multi-table inputs. Duplicate predictions do not receive complete-input credit or multiply
positional matches. Best-candidate positional-span match sums (1100 isolated,1108 shared
weights,667 public) are evaluation upper-envelope diagnostics, not source-selected production
output or probabilities. The summary explicitly retains empty and extra predictions.

For per-input matching positional/span counts, explicit DocWeave exceeds shared-weight
upstream on eight inputs, ties on11 and is worse on one: PMC4003957_018_00.png has54 versus
63 matches (69 published anchors). This counterexample remains in the report. Existing
independent RULED evidence has69 matching keys on that same source, suggesting a bounded
rule/provider composition investigation rather than another unrestricted OCR parameter search.
No per-input strategy selection was applied in this evaluation.

## Controlled scope and differences

All20 original upstream-train examples are embedded whole at150DPI and rendered300DPI.
There are no excluded cases, repaired annotations, source-specific crops or model changes.
Training overlap with the selected models is unknown. These are table images embedded in PDFs,
not a representative full-page/mixed-document/noisy-scan benchmark.

For every input, captures on actual Java21 and25 have identical rendered PNGs, decoded-object
TSVs, source hashes and default structure-table counts. capture-pairs.json records all40 JVM
capture identities. Python inference runs on the Java25 captures using the exact runtime/model
pins in ../pdf-upstream-table/. The isolated profile passes those same decoded objects and real
upstream OCR tokens through unmodified upstream postprocessing; it may use tokens to refine axes.
The shared-weight table recognizer has different image padding/resizing/PyTorch execution.

The public upstream API uses hi_res/infer_table_structure=True/eng/300DPI/yolox and explicitly
initialized pinned default model identities. It uses Poppler and detected crops; this is not
every stock default setting. Its older default table weights differ from DocWeave's v1.1-all
weights. The historical DocWeave detector profile uses Microsoft's detector and PAGE word
assignment rather than CELL refinement. These model/preprocessing/OCR/pipeline differences
prevent attributing every outcome to the Java versus Python implementation.

The Java measurement reports come from ../pdf-merge-conflicts/cells-java25.json and
../pdf-page-detection/pubtabnet-java25.json; the latter is explicitly historical evidence,
not a new end-to-end execution here. Their published row/column/anchor counts are checked
against every Python reference case. The captures themselves use committed bb15f2b default
REJECT_TABLE; the explicit-policy results are the separately verified paired891-test checkpoint.

## Evidence and reproduction

comparison.json retains all20 complete text-free comparison records, dependency/settings/source/
model/capture hashes and source-report hashes. manifest.json links all original source/model pins
and records the exact Java runtime class fingerprints; no source images, PDF derivatives, OCR
transcriptions or model binaries are redistributed. Dependency and original-asset setup is in
../pdf-upstream-table/README.md. The actual upstream dependency code is unmodified, with model
downloads disabled through its Hugging Face offline settings during the configured runs. The
separately installed pinned English NLP model is available locally.

For each of the20 PNG names in ../pdf-pubtabnet/manifest.json:

1. Run CaptureTableReferenceInput on Java21 and25 into new separate directories.
2. Verify render.png, objects.tsv, source.sha256 and docweave-tables.txt match across captures.
3. Run compare_pdf_upstream.py with the Java25 capture, the same explicit model/runtime settings,
   and a fresh output named NAME-comparison.json in one report directory. Retain failures as
   incomplete evaluation; do not remove failed inputs or claim full coverage. This run had no failures.

Aggregate all20 with the JDK-independent summary tool:

```sh
python3 benchmarks/tools/summarize_pdf_upstream.py --reports /path/to/report-directory --candidate benchmarks/review/pdf-merge-conflicts/cells-java25.json --detector-baseline benchmarks/review/pdf-page-detection/pubtabnet-java25.json --output /tmp/new-all20-summary.json
/path/to/reference-venv/bin/python -m unittest discover -s benchmarks/reference-tests -p 'test_pdf_upstream*.py'
```

The aggregator rejects missing/duplicate inputs, mixed model/runtime settings, changed source
revisions and Python/Java annotation-shape disagreements. Five additional analytical controls
prevent duplicate-match inflation, credit for extra public tables, credit for empty recognitions,
miss exclusion and complete-input credit for extra Java tables. Together with the existing six
reference metric controls,11 reference-tool tests pass; the12 ordinary tooling guards also pass.
No production changes follow from this comparison. bb15f2b CI39073695132 passed both JVMs;
its full local baseline remains891 tests/strict Javadocs/installed-engine evidence on21/25.
