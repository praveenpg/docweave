# Pinned Unstructured alignment (2026-10-22)

This pass replaces the former human source-decision prerequisites with observed behavior of the
executed reference, **Unstructured 0.27.28 with inference 1.6.13**. Newer reviewed releases are not
executed references. Everything here is a compatibility observation, not source accuracy.

All scoring is on the JVM; Python only executes Unstructured. Raw captures, source text and source
files stay outside Git. This folder holds manifests, frozen protocols and numeric reports.

## Reference captures

Each run was frozen before execution, offline, with pinned sources, models and OCR engine. Every
case succeeded; there were no timeouts or typed failures.

| Question | Strategy | Capture | Manifest |
| --- | --- | --- | --- |
| Scientific page-1 order | `fast` | Reused: full-body captures (runtime `7b913aaa…`), all three originals, plus the existing holdout capture | `../pdf-scientific-full-body/upstream-manifest.json` |
| NVIDIA parentheses | `hi_res` (default and shared table weights) and `fast` | Reused `hi_res` default `a3662e0a…` and shared `1ca552a9…`; new `fast` `adc4a513…` (1,008 elements) | `nvidia-fast-manifest.json` |
| Apple and Amazon trailing rows | `hi_res`, table inference, YOLOX, default PubTables weights, 300 DPI | New: Apple `eb59b1e8…` (145 elements), Amazon `4f769692…` (303), 68 s in total | `tables-manifest.json` |
| 1835 page number, ICC, degraded grouping, stamps | `ocr_only` through `partition_image` on the original TIFFs, both pinned models | New: 8 of 8 succeed, 55 s in total | `scan-images-manifest.json`, launcher `../../tools/capture_unstructured_scan_images.py` |
| Acceptance breadth | `fast` | New: 10 of 10 local originals succeed, 14 s in total | `acceptance-manifest.json` |

**Freeze timestamps.** The hand-entered `frozen_at` fields in `tables-manifest.json` (18:30Z) and
`scan-images-manifest.json` (18:40Z) are wrong; both runs started at 17:36Z. They were left
unchanged because each executed runtime record pins the manifest hash. File times confirm the
order: targets 17:35:39Z, table manifest 17:35:43Z, table run 17:36:08Z, scan manifest 17:36:40Z,
scan run 17:36:45Z. The first table launch failed before execution (missing `/usr/bin/time`) and
created no output; its log is retained privately.

## Reference decisions

### Apple and Amazon membership: excluded upstream, compatible

`ClassifyUpstreamTableMembership` checks frozen private targets (`fd6132b6…`) against every Table on
the declared page, by cell text and by geometry (`table-membership-java21.json`):

| Row | Upstream table extent (pt) | Row baseline (pt) | Frozen text class | Geometric cover |
| --- | --- | --- | --- | --- |
| Apple disclosure heading | 129.7–678.6 | 685.8 | `OUTSIDE_TABLE` | no |
| Apple income-taxes row | 129.7–678.6 | 700.0 | `ABSENT`; 3 of 3 items in separate non-table elements | no |
| Amazon diluted-shares row | 78.2–514.3 | 527.3 | `ABSENT`; 5 of 5 items in separate non-table elements | no |
| Apple control (ending balance) | 129.7–678.6 | 657.2 | `ABSENT` (OCR-damaged cells) | yes |
| Amazon control (basic shares) | 78.2–514.3 | 511.6 | `TABLE_TEXT` | yes |

The separate-elements count was added after the frozen classes were first reported. That first
report is retained privately, and the classes are unchanged. Upstream emits each target label and
value as its own `UncategorizedText` below the table, so DocWeave's exclusion is the compatible
behavior. The painted row-band design is therefore not pursued.

### NVIDIA parentheses: tight everywhere, compatible

`CensusParenthesisPresentation` (`nvidia-parentheses-java21.json`) counts tight `(5,491)` against
spaced forms:

| Source | Tight | Spaced or split |
| --- | ---: | ---: |
| Upstream `fast` native text | 110 | 0 |
| Upstream `hi_res` default, table cells | 100 | 0 |
| Upstream `hi_res` shared, table cells | 95 | 0 |
| DocWeave defaults | 110 | 0 |

The spaced form exists only in the issuer HTML reference. Strict historical scores are unchanged.

### Historical scans: stored samples, page number in scope

`CompareUpstreamScanImages` (`scan-images-java21.json`) decodes each original on the JVM twice and
compares both with the array upstream recognized:

- **ICC.** For all three profile-tagged originals (1835, 1778, 1780), upstream's array equals the
  stored samples and differs from ImageIO's colour-managed RGB. Upstream ignores the embedded
  profile. 1879 is untagged bilevel, and its boolean array matches neither encoding, so ICC
  provenance is not established there.
- **1835 page number.** On the original-image path it is covered by an `UncategorizedText` element
  under both models. On the colour-managed container it is absent.
- **Equal pixels.** Upstream's recognized 1835 text is byte-identical to DocWeave's opt-in
  stored-sample harness (`docweave.pdf.historical.stored.samples=true`): `5ba24e01…` German,
  `b21aaabd…` Fraktur. The same pixels cost both pipelines Fraktur quality: 50→63 edits for
  upstream and 51→63 for DocWeave.

| Case | Upstream original image | Upstream container |
| --- | ---: | ---: |
| 1835 German / Fraktur (1,059) | 107 / 63 | 107 / 50 |
| 1879 German / Fraktur (3,049) | 290 / 333 | 300 / 334 |
| 1778 German / Fraktur (948) | 435 / 407 | 413 / 374 |
| 1780 German / Fraktur (322) | 106 / 76 | 108 / 85 |

These are character edits, with the reference code points in brackets.

- **Stamps and rulers.** On 1780, four right-margin elements overlap no reference line under both
  paths and both models, and are emitted as `Title` or `UncategorizedText`. Upstream includes them.
- **Degraded grouping.** On the original image, the 1778 list and surrounding prose form one
  `NarrativeText` with no column separation. The container path splits them into list and title
  elements.

DocWeave accepts PDFs, not TIFF originals, so no runtime decode change follows. The colour-managed
container remains the default evaluation protocol; the stored-sample harness is the
upstream-equivalent protocol.

### Whitespace presentation, a by-design difference

Upstream replaces every run of Python `str` whitespace in non-table text with one ASCII space
(`unstructured/partition/pdf.py` lines 1269–1273, plus the NBSP translation in `cleaners/core.py`).
DocWeave keeps NBSP, thin and narrow no-break spaces from the source. This explains the
`(p < 0.0001` and `37 °C` token differences in the acceptance comparison. It is not changed.

## Implementation: explicit native inline-script runs

**Diagnosis.** On page 1 of all three development papers, native line grouping places the raised
affiliation markers of the author line on their own line, before the names. Upstream keeps each
marker inline. Cause: `PdfGlyphLines` starts a new row for any baseline more than 2 units away.

**Candidate.** `PdfOptions.InlineScripts.NEARBY_SMALL_GLYPHS`, opt-in, with `PRESERVE` as the
default. It applies the existing whole-run cell rule (`PdfSmallGlyphRuns`, used by
`CellTextJoin.INLINE_SCRIPT_RUNS`) to ordinary native text after table staging. Each run is
compared only with body glyphs in its baseline band, visited in input order, and the result equals
the whole-input rule.

The frozen hypothesis wrongly said the run rule was also used by tagged paragraphs; those use a
per-glyph rule. The parameters it specifies are those of the run rule, which is what was built.

**Frozen validation** (`scientific-superscript-hypothesis-frozen.txt`, `9c03eafe…`):

| Prediction | Outcome |
| --- | --- |
| P1 page-1 located elements, strict text | **Failed.** No change on any paper. The markers now match upstream, but the source's narrow no-break spaces (U+202F) still block strict location. |
| P2 complete-body strict paragraphs | 58 / 58 / 59 → **60 / 60 / 61** of 143 (LINES / COLUMN_PARAGRAPHS / COMPOUND_WRAPS); reversed pairs 0 |
| P3 holdout page 1, strict | Located 10→10, reversed 2→2 |

The policy was parked and reverted. After the other tasks, it was revisited with one distinct,
source-derived hypothesis (`scientific-superscript-revisit-frozen.txt`, `0a45455f…`): compare both
sides in upstream's own whitespace presentation. This is a post-failure protocol, and weaker
evidence than P1.

| Revisit, page 1, upstream presentation | recent | next | eLife | holdout (transfer) |
| --- | --- | --- | --- | --- |
| LINES | 33→34 | 29→30 | 4→5 | 12→13 |
| COLUMN_PARAGRAPHS | 33→34 | 29→30 | 6→7 | 12→13 |
| COMPOUND_WRAPS | 32→33 | 28→29 | 6→7 | 12→13 |

Reversed pairs are unchanged in every row (0, 3, 0 and 2). R1 and R2 hold, so the option was
restored unchanged in behavior.

**Order fix during productionization.** A new seeded test found that the first banded
implementation visited band members in baseline order. Because the first supporting body fixes the
comparison baseline, that could differ from the whole-input rule. Band members are now visited in
input order, and the test proves equality on 300 seeded pages. Every page-1 report was regenerated
on the corrected code and is keyed-identical to the superseded runs, which are retained privately.
P2 was rerun through `PdfScientificReadingOrderTest` (`scientific-inline-scripts-java21.json`);
its no-policy rows equal the historical `../pdf-scientific-full-body/native-java21.json` exactly.

**Broader transfer** (`acceptance-*-java21.json`). Ten local originals, defaults versus defaults
plus the policy:

| Measure | Defaults | With policy |
| --- | ---: | ---: |
| Matched tokens (of 29,794) | 28,970 | 28,992 |
| Missing | 824 | 802 |
| Extra | 635 | 486 |
| Ordered anchors | 5,944 / 7,131 | 6,008 / 7,200 |

Alphabet, PeerJ and US-001 gain. The only regression is Scientific Reports, one fewer matched
token (5,223→5,222), while its extras fall 187→118. Six documents are unchanged.

**Regressions.** The cell inline-script source regression
(`../pdf-cell-inline-scripts/java21.json`) reproduces all six result rows exactly. Defaults are
unchanged.

**Owned tests:**

- `PdfNativeInlineScriptsTest` (5): raised and lowered markers join their line under two layouts;
  long runs, distant small text and competing baselines keep historical lines; rotated pages are
  unchanged; ruled table cells are untouched; option defaults, copies, compatibility constructor
  and null rejection.
- `PdfSmallGlyphRunsTest` (+2): seeded band-versus-whole-input equality, and shared work limit
  and interruption on the page path.

## Parked or unsupported

- **Degraded printed rule, V2 local ink** (`degraded-local-ink-frozen.txt`, `2738f5eb…`;
  `degraded-local-ink-diagnostic.txt`). Otsu inside the OCR text area (threshold 154 on 1778) still
  finds no rule run of 8 word heights on 1778. No acceptance on 1835, 1879 or 1780. Parked. Upstream
  separates no columns either, so further work here is reference-quality only.
- **TATR proposal coverage.** A proposal design needs independent table labels plus recall and
  false-positive evaluation; none exist locally. Unsupported in this pass.
- **Upstream fixture corpus.** The pinned `example-docs` fixtures are not available locally, and
  fetching them would need download authorization. The acceptance list uses local originals
  instead; all ten are development data from earlier passes, not held-out cases.

## Reproduction

The upstream runtime is `/tmp/docweave-upstream-pdf-venv`. Put the pinned Tesseract on `PATH` with
its `LD_LIBRARY_PATH`. Set `HF_HOME` to the populated asset cache, plus `OMP_NUM_THREADS=4` and
`OMP_THREAD_LIMIT=1` for tables. Always use fresh output paths.

```sh
python benchmarks/tools/capture_unstructured_tables.py tables-manifest.json SOURCES ASSETS NEW_DIR
python benchmarks/tools/capture_unstructured_scan_images.py scan-images-manifest.json TIFFS TESSDATA NEW_DIR
timeout 180 python benchmarks/tools/capture_unstructured_reading.py nvidia-fast-manifest.json SOURCES NEW_DIR
timeout 600 python benchmarks/tools/capture_unstructured_reading.py acceptance-manifest.json SOURCES NEW_DIR
```

JVM tools compile with `javac --release 21` against the validation runtime and test classpath, with
`-Djava.awt.headless=true`:

- `ClassifyUpstreamTableMembership MANIFEST TARGETS SOURCES CAPTURES REPORT`
- `CensusParenthesisPresentation PDF SHA256 FAST HIRES_DEFAULT HIRES_SHARED REPORT`
- `CompareUpstreamScanImages MANIFEST TIFFS IMAGE_CAPTURES CONTAINER_CAPTURES REPORT`, run from
  the repository root
- `CompareUpstreamPageOrder MANIFEST SOURCES CAPTURES 1 PROFILES REPORT [--upstream-whitespace]`
- `CompareUpstreamTextAgreement MANIFEST SOURCES CAPTURES REPORT [--inline-scripts]`

Each tool except the scan comparison has a `--self-test` mode. P2 is reproduced with:

```sh
mvn -o -pl docweave-validation -am test -Dtest=PdfScientificReadingOrderTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Ddocweave.pdf.scientific.reading.cache=ORIGINALS \
  -Ddocweave.pdf.scientific.reading.manifest=benchmarks/review/pdf-unstructured-alignment/scientific-inline-scripts-manifest.json \
  -Ddocweave.pdf.scientific.reading.report=NEW_REPORT
```
