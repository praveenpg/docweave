# Source-tagged table development controls

The manifest was frozen before real candidate extraction. This explicit strategy
uses supported original structure-tree roles/spans and page MCID glyph ownership,
then glyph-owning native fallback and disjoint model fallback. No reference region,
publisher text, model threshold change or reference rewrite enters extraction.
Existing factory behavior remains unchanged.

## Owned contract

Nine PDF tests cover source TH/TD, row/column spans, all declared page quarter turns,
optional geometry, explicit date joining, retained ordinary text/images, aliases,
structure-element ActualText refusal, malformed/missing/repeated markers, forms, link annotations,
unsupported/class-dependent/current-revision attributes, cross-page/nested tables,
empty-cell inference and conflicting unclaimed text, disjoint native fallback,
provider failures, combined discarded-anchor budgets, cancellation and constructor
policy guards. A diagnostic parser test preserves mixed TH/TD row-major order.
The existing chunk/JSON test now verifies whole-source empty-cell counts survive
provenance but are removed from structured and text-only fragments.

These are authored contract controls, not independent real-world accuracy labels.
Source metadata and optional geometry scope are documented in docs/pdf-support.md.

## Fixed original-source replay

All21 existing selected regions were replayed on actual Java21/25, whole original
documents, unchanged pinned detector/structure graphs,150DPI/four threads,
KEEP_HIGHER_SCORE/PARTIAL_TEXT and explicit WRAPPED_ISO_DATE. Set
`-Ddocweave.pdf.native.tagged.first=true` on CaptureNativeModelTables together with
its existing native-first argument and ISO-date property. Diagnostic scoring also
requires `-Ddocweave.pdf.scientific.tagged.first=true` so historical profiles are
not silently relabelled. All original source/reference manifests remain authoritative.

All42 paired captures are equal to the preceding ISO-date baseline after removing
only the explicitly changed strategy/provenance fields. Source frames also match.
None of these real/synthetic replay cases accepts a tagged table: this is fallback
preservation evidence, not a real-source extraction improvement. Numeric hashes
and all denominators are retained in replay-results.json; raw captures stay off-Git.

The recent PLOS original has one recognized16x9/136-anchor source-tag grid, but40
references lack usable page-scoped ownership in the current contract. Whole-table
fallback therefore preserves the prior model result:131/136 exact publisher logical
span/text anchors and9/9 source-labelled headers,137 observed anchors with4 missing/
5 extra logical keys. No partial source table or guessed content is emitted.
Original publisher logical labels do not establish physical PDF border truth.

## Limits and verification

No OCR, arbitrary ActualText substitution, class-map span resolution, nested or
cross-page tagged table assembly, form-content ownership, arbitrary word joining,
empty row/column geometry or confidence calibration is added. Incomplete source
tags may leave useful structure unused by design. Modern business/scientific breadth,
historical/degraded scans and the final version-pinned comparison remain open.

Verification results are retained in verification.json. Upstream-status.json records
the refreshed official metadata and known drift from the historical released pin;
old goldens remain unchanged. Release preparation is deferred; these are development
changes, not a new Central release.

A subsequent [resolved-text audit](../pdf-tagged-resolved-text/README.md) identifies
content-stream ActualText as the reason for the40 rejected source references in this
historical baseline; its before/after results are recorded separately.
