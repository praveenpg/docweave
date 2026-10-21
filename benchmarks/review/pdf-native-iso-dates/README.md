# Explicit native wrapped ISO-date joining

The recent publisher-labelled scientific baseline identifies a concrete native
cell-text limitation:15 dates are wrapped after an existing hyphen and receive a
joining space. This opt-in policy interprets an entire cell as a valid ASCII ISO
date only under the source-line constraints documented in docs/pdf-support.md.
It changes neither model predictions nor source ownership. SPACE_SEPARATED remains
the default. Arbitrary word dehyphenation and header topology repair are excluded.

The candidate manifest was frozen before candidate extraction against the same
original inputs/reference manifests. Both actualJava21/25 use the same pinned
models, complete originals,150DPI/fourthreads/PARTIAL_TEXT and KEEP_HIGHER_SCORE.
The capture helper's explicit -Ddocweave.pdf.native.iso.dates=true applies this
single policy uniformly. It supplies no reference labels/ROIs to extraction.

## Results and limits

| Fixed recent publisher table | Default | Explicit date policy |
| --- | --- | --- |
| Exact logical span/text anchors |116/136|131/136|
| Matching logical span keys |132/136|132/136|
| Exact independently labelled headers |9/9|9/9|
| Observed shape/anchors |16x9/137|16x9/137|
| Source dates joined |0|15|

Both full15-page originals succeed. Geometry, spans, model provenance and all
nondate cell text are unchanged. One hyphenated-word text mismatch and the original
four missing/five extra logical span keys remain. This is development evidence
from one publisher, not held-out extraction accuracy or physical border fidelity.

All20 other fixed selected captures compare identically before/after on eachJDK:
10 prior real regions, two newly selected ordinal external regions and eight
synthetic controls. Unchanged-replays.json retains all40 comparison receipts with
source-frame and capture hashes. In particular, both synthetic borderless wrapped
header cases still fail; this policy does not merge their rows. Prior issuer HTML
was unavailable with403 in the earlier replay; no new59-row issuer-reference check
is claimed here. Unchanged captured output does not constitute new reference access.

Owned tests cover valid/leap/invalid dates, digit-group splits, additional text,
non-ASCII digits/whitespace, same-baseline fragments, alignment, rotation, both
native factories, optional geometry, unchanged outside text/IDs, original cell
budget enforcement, constructor defaults and null policy. Whole-table change
counts survive JSON and original-element provenance, but structured/text fragments
omit the source-wide count. Public source policies remain immutable and helper
implementations hidden. No dependency, baseline Java or default strategy changes.

## Compatibility and verification

PdfNativeLayoutOptions gains cellTextJoin as its fourth record component. The old
three-argument constructor/accessors remain. LegacyNativeLayoutConsumer was compiled
at release21 against the old source API from4c53394, then executed against the new
classes on actual21/25; the old API classes are excluded from the runtime classpath.
Record patterns/reflection/serialization and equality/hash/string shape consumers
must account for the additional component. See the explicit upgrade notes.

Strict upstream freshness checking still reports the known historical-pin drift:
release0.27.28/main1b02b4d, six commits ahead of historical0.27.27. The official response
receipt is retained in upstream-status.json; old goldens/pins were not rewritten.
Full offline regression verification excludes that deliberately opt-in online
freshness assertion. Release notes/changelog are deferred drafts only; no candidate,
tag, GitHub release, signing or Central upload was performed for this check.
