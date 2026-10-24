# Java validation tooling

Development-only Maven module for independent source/evidence validation. All code
is in src/test, all dependencies are test-scoped, and installation/deployment are
skipped. It is not a product artifact, public API, ServiceLoader provider or runtime
dependency. Implementations remain package-private behind an internal interface.

## DOCX ownership

```sh
mvn -pl docweave-validation -am test
```

This runs JUnit source-oracle tests and real extraction of the pinned owned corpus
through DocWeave's public interfaces. The JDK DOM/ZIP oracle does not use POI or
production parser internals. It preserves ownership-v1's accepted-revision policy,
body/text-box owner order/type and parent checks, duplicate-occurrence protection,
other-part exclusion and explicit unsupported-markup boundaries. Source XML rejects
DTDs/entities and exceeds neither 16 MiB nor depth 256. Longest-first substring
allocation remains diagnostic; no exact-text, cell-order or rendering claim is made.

JUnit compares every owned ownership summary with the saved Python-era report.
Additional tests cover Unicode whitespace/code-point ordering, immutable results,
duplicate output IDs, XML rejection, input tampering, contract failures and exclusive
report creation. The Python ownership implementation/tests have been retired, not
retained as a second implementation. Historical JSON reports and input hashes remain
unchanged; their recorded Python source hashes refer to repository history.

Reports go to unique files in target/docx-ownership and are retained by private CI.
They contain structural paths, counts, hashes, Java version, source fingerprints
and ordered actual classpath fingerprints, including class directories in a reactor
build. Surefire may list a missing empty classes directory; its absence is recorded.
Extracted text, image bytes and XML are never written to these reports. Selected
manifest anchors remain visible as in the historical reports.

For a separately fetched, hash-pinned external corpus, provide all three properties
and a fresh output path (paths below are relative to repository root):

```sh
mvn -pl docweave-validation -am test \
  -Ddocweave.corpus.manifest=benchmarks/corpus/docx-forms/manifest.json \
  -Ddocweave.corpus.cache=/local/cache \
  -Ddocweave.corpus.report=/tmp/new-java-forms-review.json
```

No downloads or external-corpus requirements apply to the default reactor.

## Corpus inventory and legacy review

Inventory and legacy substring/upstream-reference diagnostics are now Java/JUnit.
The retired Python scripts and tests are not kept as duplicate implementations.
Historical JSON reports retain their original Python-source hashes.

```sh
mvn -pl docweave-validation -am test \
  -Ddocweave.inventory.manifest=benchmarks/corpus/docx-forms/manifest.json \
  -Ddocweave.inventory.cache=/local/cache \
  -Ddocweave.inventory.report=/tmp/new-inventory.json
```

Inventory counts stored document.xml structures including revision/fallback branches
and named stored-part files, not relationship reachability or rendered content.
Optional docweave.inventory.expected checks document fields against a saved inventory.
All inventory paths are required together; reports use fresh paths and no source text.

For legacy review, use the corpus properties above plus
-Ddocweave.corpus.baseline=substring. Optional docweave.corpus.reference names actual
upstream-generated JSON; input hashes must match and duplicate reference cases reject.
Reference provenance/file hashes and diagnostic metrics are preserved. Java contracts
determine checks_passed; upstream differences are diagnostic, never ground truth.
The default remains ownership-v1, which rejects references without defined mappings.

The legacy baseline preserves global normalized substring presence, including its
limitations: no order/multiplicity/ownership proof, nested paragraphs reject, and
paragraph enumeration under deleted block ancestors can produce unmatched probes.
Inventory and accepted-owner selection deliberately have different policies. Both
external forms and three POI inventories match saved fields; NRS historical results
were not rerun. Real pinned native reference ingestion is covered by JUnit; synthetic
reference-schema tests are clearly marked and never saved as upstream evidence.

## Saved Word evidence

WordEvidenceReplayTest runs the actual Java extractor against both saved packets
and compares 16 supported cases without a VM, Python or a launcher classpath. A pinned
catalog guards original manifests, DOCX bytes, raw first/repeated observations and
historical reports. Results remain human-review pending even with zero differences.
Four direct-heading controls retain provenance/policy coverage outside this comparator.

Default directories are unique under target/word-evidence. Choose a fresh path with
`-Ddocweave.word.output=/tmp/new-word-run`. Tests retain originals, archive historical
candidates and save new candidates/comparisons with source/runtime fingerprints.
Historical-difference parity and adversarial tests guard the migrated instrument.
See [saved Word replay](../benchmarks/word-style-validation.md) for properties that
compare a deliberately new observation file without changing the pinned evidence.

## Upstream freshness

The reviewed checkpoint is [upstream-status.json](../benchmarks/upstream-status.json).
Default tests check consistency with our pinned reference. Explicit online checks
query official GitHub release/main/tag/compare metadata using the Java HTTP client:

```sh
mvn -pl docweave-validation -am test -Ddocweave.upstream.check=true
```

Two independent metadata requests use bounded virtual-thread tasks. Requests have
connect/request timeouts; API errors and incomplete comparisons fail. Reports are
saved under target/upstream-status before drift assertions. A new release, tag
identity or unreviewed main commit fails the check; references are never rewritten
automatically. Review changes, update the checkpoint explicitly, and regenerate
affected reference cases before changing compatibility claims. Default CI is
offline with respect to this check; workflow_dispatch offers an explicit online flag.

On 2026-10-16 the reference commit 347fbbfd matches released 0.27.27. Reviewed main
3253c2b0 is five commits ahead: optional language detection, unknown-element/table-caption HTML,
DOCX style-cache reuse and XLSX sparse-row handling. A scoped HTML wrapper/content-rule subset
is implemented; caption representation, math/metadata/DOM repair and other policy differences
remain explicit. No fresh main parser execution or full main parity is claimed. See
[upstream tracking](../docs/upstream-tracking.md) and its linked review evidence.

## Remaining migration

DOCX ownership, inventory, legacy review and saved Word replay/comparison are Java/JUnit.
The conditional-style audit is also Java/JUnit, using the internal DocxStyleProbe interface,
secure independent DOM inventory/derivatives, and direct public-API extraction. Run with
`-Ddocweave.styles.manifest=... -Ddocweave.styles.cache=... -Ddocweave.styles.report=/tmp/fresh.json`
(all three required). Reports contain only aggregate hashes/counts plus runtime/source provenance;
see [probe version 2 and historical differences](../benchmarks/corpus/docx-styles/README.md#repeat-offline).
No Windows VM is needed. Follow-up/heading fixture generators and structural tests are also Java/JUnit. The internal
WordFixtureGenerator service produces fresh owned input packets only, with verified seeds and
no candidate/Word observations. Optional `docweave.fixtures.kind` (`followups` or `outline-controls`),
`docweave.fixtures.source` and `docweave.fixtures.output` are required together. Default tests
retain fourteen-case semantic reproduction artifacts under `target/word-fixtures`.
The original standalone Java base-packet generator remains a separately tracked helper.
Saved-output comparison is also Java/JUnit via the internal SavedOutputComparator interface,
with 1767 historical Unicode/directional similarity vectors, 14 complete reports and four encoded
numeric edge reports. `docweave.compare.reference/candidate/report` requires all three properties
and a fresh report; differences are recorded before JUnit fails. The classpath CLI preserves
0/1/2 exit codes; see [comparison commands](../benchmarks/README.md). Reports/provenance are retained
under `target/saved-output-comparison`. The remaining Python tests guard actual upstream generation
or execution (see PDF benchmark scorers below).
Python upstream reference generators remain appropriate because they execute the
actual Python Unstructured implementation. Formatting helper migration is separate.
The normal Java library and Maven Java tests do not require Python.


## PDF benchmark scorers

The DocWeave benchmark scorers formerly written in Python run on the JVM. They are package-private
implementations of the internal `BenchmarkScorer` interface, launched by `ScoreBenchmarkCaptures`:

```sh
java -cp "docweave-validation/target/test-classes:$DOCWEAVE_CLASSPATH" \
  io.docweave.validation.ScoreBenchmarkCaptures <command> [options]
```

Commands:
- `business-cell-text`
- `tiled-regions`
- `detector-structures prepare|compare`
- `compact-layout`
- `ruled-scans`
- `raster-rule-prototypes`
- `raster-rule-audit`
- `scan-detectors-verify`
- `pdf-upstream-summary`

`PythonJson` reproduces the retired tools' JSON exactly: Python `int` and `float` typing, `repr`
floats, `ensure_ascii`, `indent=2` and `sort_keys`. `ScoringFiles` reproduces the `csv`,
universal-newline and `str.strip` semantics. Report bytes therefore stay comparable.

Parity evidence:
- **Retained reports:** every retained cTDaR report is regenerated byte for byte
  (`CtdarScorerReplayTest`, opt-in with `-Ddocweave.scoring.ctdar.cache=DIR`). The all-20 upstream
  summary is regenerated from its own reports, apart from the harness hash.
- **Frozen vectors:** `PythonScorerParityTest` replays vectors frozen from the retired functions,
  including failure paths.
- **Per-method tests:** each retired Python test method has a same-named JUnit test.

Python remains only where it executes or guards Unstructured:
- the upstream YOLOX proposal harness, whose metrics `scan-detectors-verify` recomputes on the JVM;
- the Unstructured table comparison launcher;
- the reference generators and their guards.

See the [migration evidence](../benchmarks/review/python-scorer-migration-2026-10-22/README.md).

## Additional complex-corpus checks

The fetch-only [complex DOCX corpus](../benchmarks/corpus/docx-complex/README.md) adds independently
maintained Apache POI/LibreOffice regression samples. `required_source_features` pins positive
stored-XML counts before extraction. `expected_nested_descriptors` opts into real metadata-preserving
extraction in both stored-part modes, comparing all root identities/text/metadata (after stripping
only nested-table sidecars), warnings and reviewed descriptor counts. `minimum_deleted_fragments_checked`
requires ownership mode and checks distinct body delText fragments >=24 code points that do not occur
in accepted body owners. Short/repeated/ambiguous and textbox fragments are excluded; only hashes/counts
are reported. This is a limited negative-content check, not full revision/rendering fidelity.

Use the existing `docweave.corpus.manifest/cache/report` options with the new manifest. Default Maven
tests validate the added checks with owned fixtures and need no external corpus download or VM.
Local external runs and their source/runtime fingerprints are recorded in the corpus reports;
normal hosted CI does not re-download or rerun external binaries.


## Omission warning audit

`expected_warnings` optionally pins an exact multiset of `{code,message}` objects, preserving
repeated diagnostics while ignoring their traversal order. Existing `expected_warning_codes`
checks remain separate. Every corpus report now includes detailed warning objects; when detailed
expectations are configured, `warning_details_match` contributes to the overall contract result.

`required_source_omissions` optionally pins independent stored-XML clues: bare VML rectangle count,
rectangles with literal Office `hr="t"`, and unresolved direct table-style IDs (missing or wrong type).
This does not resolve inheritance/defaults, render shapes or select revision/AlternateContent branches.
See [the pinned omission audit](../benchmarks/review/docx-omission-audit/README.md) for the six valid
warnings from the complex corpus, exact commands and bounded interpretation. No production parser
behavior or warning API was changed by this audit; original corpus reports/manifests remain intact.

## Independent native PDF reading diagnostics

`PdfReadingCorpusTest` optionally validates pinned PDFBox PDFs and saved expectations with
`docweave.pdf.reading.cache` and `docweave.pdf.reading.report` (both required; fresh report path).
Default CI does not fetch an external cache. The [manifest, reproduction and retained differences](../benchmarks/review/pdf-reading/README.md)
separate native character-inventory/paragraph-order preservation from correct article reading order.
No third-party binary or full text is committed, no upstream PDF execution is invented, and saved
order/ligature differences remain explicit.

## Independent PDF rotation diagnostics

`PdfRotationCorpusTest` uses `docweave.pdf.rotation.cache` plus `docweave.pdf.rotation.report`
(both required; fresh report). The [manifest/review](../benchmarks/review/pdf-rotation/README.md)
pins two independent PDFBox rotation samples and saved text expectations. It validates byte identities,
native-inventory preservation and real JSON round-trip; saved-order comparisons remain explicit
separate diagnostics. Default CI does not download external inputs. Offscreen owned renderer tests
run headlessly in the PDF module and require no desktop or Windows VM.

Standalone example/benchmark Java sources are compiled with `--release 21` by
`StandaloneSourceCompatibilityTest`. They use standard classes/main methods; no preview or Java 25
compact-source syntax is required. CI verifies the reactor on Java 21 and Java 25 separately.
