# Explicit native compound-word wrap spacing

`COLUMN_PARAGRAPHS_COMPOUND_WRAPS` keeps the existing native column/paragraph
groups and order, adding explicit physical-hyphen boundary interpretation. A hidden
source strategy reconstructs every grouped line's exact text and bounds from unique
original upright glyph objects. Only then may the existing bounded letter-hyphen/
letter rule omit a generated inter-line separator. Every physical source character,
including the hyphen, remains. No dictionary, source reference, OCR or model is used.

Native baseline indexing limits candidate lookup; binary search and ownership
probes consume the existing shared page work budget. Whole ownership ambiguity or
reconstruction mismatch retains all separators. Explicit edge whitespace, numeric
or punctuation boundaries and unsupported rotated frames remain unmodified. Groups
do not cross table/image/tagged-paragraph/page barriers. Existing layout modes,
tables, OCR and source-owned paragraph policies are unchanged. Modified groups
declare `retained_hyphen_wraps_v1` and the omitted-separator count; IDs may change
where text changes.

## Complete development replay

All five now-observed scientific originals and21 main-body publisher caption
references remain. The manifest freezes initial and indexed pre-evaluation code
identities before any original candidate replay. The refinement changes lookup
cost, not line proofs, thresholds, references, scoring or source selection.

| Uniform profile, complete bodies classified | Existing columns/edge captions | Native compound columns/edge captions |
| --- | ---: | ---: |
| Native lines |7/21|7/21|
| Native column paragraphs |18/21|19/21|
| Source-tagged tables / native lines |7/21|7/21|

Scientific Reports improves4/5→5/5 under columns, preserving the physical hyphen
in the wrapped compound. PeerJ remains4/6: two printed hyphens are absent from XML,
and this policy deliberately retains them. No strict reference or partial-unit
credit is repaired. All15 reference trees remain exact and all10 line-profile
records reproduce exactly. These are development results, not held-out caption
precision, physical box accuracy or a general PDF completion percentage.

The separate `CompareNativeCompoundText` JVM replay extracts both policies for
all15 fixed source/profile pairs. Every legacy full-text/caption hash reproduces
the frozen prior evidence. Element types/order/counts, geometry and all metadata
except two declared native-wrap fields remain equal. Ordered text after excluding
only ASCII space is exact for every paired unit; each text-length decrease equals
its declared omitted-separator count. Unchanged text units retain exact element
identity. These necessary invariants do not independently prove which spaces were
generated; exact source-line reconstruction and owned explicit-source-space controls
establish that separate constraint. The report preserves every case and any failure.

All18 fixed image-negative observations remain exact with unchanged PNGs and zero
captions under the new layout. They are metadata variants of one visual layout.
The earlier unsuccessful source-tagged paragraph composition remains separately
recorded; it did not fix this ordinary-native fallback caption.

## Validation

Four owned JVM tests cover native and source-table public factories, physical
hyphen/geometry preservation, source edge whitespace, numeric/punctuation boundaries,
ordinary text, missing/overlapping/mismatched/rotated ownership, tagged-paragraph
barriers, cancellation and exhausted shared page work. The resource control also
includes5,000 unrelated native positions with only1,000 work steps remaining;
baseline indexing succeeds without rescanning those positions for each line.
Existing10 reading-order,8 source-hyphen and6 caption tests remain green.
Full final Java21 Maven verify passes1,169 tests with zero failures/errors/skips,
including strict public Javadocs; strict private Javadocs and formatting pass.
Java25 verification and publication remain deferred.

The first test-helper compilation omitted its checked IOException declaration;
that declaration was corrected. Strict private Javadocs exposed missing parameter
documentation on an existing private gutter record; both components are now documented.
Initial and final source identities retain these non-semantic documentation changes.

## Reproduction

Reconstruct the prior edge-aligned three-case and one-case scorers, then apply the
two layout-only patches here. The column-labelled profile explicitly selects
`COLUMN_PARAGRAPHS_COMPOUND_WRAPS`; the two line profiles remain unchanged. Compile
the scorer populations separately with Java21 and the reactor/runtime classpath,
then execute the same original manifests/caches with headless AWT and fresh report
paths. Both transfer cases use `docweave.caption.transfer.titles=true`; Unicode
normalization remains RAW. The scorer/reference parser is unchanged.

Apply the negative layout patch to the prior edge-aligned image verifier and run
all qualified sources with headless AWT. `CompareCaptionReports.java` takes repo
root, experiment directory and a fresh report; inputs are `old.json`, `peerj.json`,
`scirep.json` and `negative.json`. It preserves every reference and unchanged line
profile; column text changes are assessed by the separate preservation replay.

Compile `benchmarks/tools/CompareNativeCompoundText.java` with the Java21 validation
test/runtime classpath. Supply a private TSV with exactly five rows containing safe
case ID, original PDF path, byte length and SHA256. Source files must match the three
existing manifests. Run from the repository root with headless AWT and a fresh
report. The tool validates original bytes, fixed baseline reports and every paired
unit before declaring success. The TSV location hash may change when caches move;
original file hashes remain authoritative. No raw source, caption transcription,
private cache path, rendered page or external model is committed.
