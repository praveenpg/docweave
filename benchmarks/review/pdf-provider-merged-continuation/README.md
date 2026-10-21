# Declared merged-provider-header continuation

The explicit `PdfOptions.TableContinuation.REPEATED_HEADER_LAYOUT_WITH_PROVIDERS`
policy admits bounded provider-declared merged header prefixes. It includes the
previous single-header/prefix behavior without changing earlier policies. Native
text, page word OCR and separately recognized cell text all use the same private
matching implementation. No dependencies, provider declarations or defaults change.

## Evidence and reproduction

`PdfProviderContinuationTest` owns every source PDF, position, declaration and OCR
callback. These fixtures test integration contracts; callbacks are not measured
OCR/model accuracy. No external documents or models are downloaded or executed.

```sh
mvn -B -ntp -pl docweave-pdf -am test \
  -Dtest=PdfProviderContinuationTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -ntp verify
```

Java21 full verification: **1,191 tests, zero failures/errors/skips**, including strict
public Javadocs. Strict private Javadocs also pass for the changed production classes
and integration test. Further parity/provenance assertions in the same ten-method
focused suite pass after the full run; production sources are unchanged between runs.

- Two header layouts: a horizontal merge and a vertical merge wholly inside a
  declared two-row header. Each crosses three physical pages.
- Three text routes, with and without serialized cell coordinates: **12 chains,
  36 tables and24 links**. Every source page/table remains in the output.
- Complete baseline element IDs/text and metadata compare exactly after removing
  only the added continuation link. Links refer to actual first/previous element IDs.
- The earlier prefix policy leaves both merged layouts unlinked. The new policy's
  single-row and unmerged-prefix results equal the corresponding earlier policy.
- Equal expanded text with a different span topology cannot match. Changed years,
  recognition families and provider provenance cannot match either.
- Blank/Unicode-spacing header labels, excessive text, blank bodies, body-crossing
  spans, jagged boundaries, missing slots and unobserved internal column edges supply
  no matching evidence. Source extraction remains independent of signature support.
- A12-case rows/columns matrix checks two through five declared header rows and
  two/eight/nine columns. Four-by-eight is admitted; four-by-nine and all five-row
  prefixes are rejected. Merged covered positions count toward the32-slot ceiling.

## Limits

Header roles come exclusively from the caller's provider declaration. The policy
never guesses roles, reconstructs rows, merges page-local tables or removes repeated
headers. Model predictions with unknown header roles remain ineligible.

Each header anchor must end within the declared two-through-four-row prefix.
Every header slot must have one owner. Each atomic column edge must be observed,
and all header/body anchors must agree exactly on shared edges. Header span topology
is retained separately from normalized text. Existing frame, page-edge, isolation,
corrected-OCR exclusion and recognition-family/provenance checks remain in force.

Existing512-unit combined-column and4096-unit aggregate signature limits count
expanded logical slots, so a large spanning label can exhaust the bound sooner than
an unmerged label. Unsupported evidence means no continuation link, not a source
text edit or an extraction failure. Matching still permits the existing two-page-unit
boundary tolerance between page-local signatures.

This is functionality evidence on owned sources. It does not improve or rescore the
NVIDIA original-source0/110 strict-row result, establish learned-header accuracy,
or close broad PDF quality/acceptance gates. No Java25/release/publication occurred.
