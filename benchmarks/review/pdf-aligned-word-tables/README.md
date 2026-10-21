# Explicit aligned-word scanned tables

Opt-in ALIGNED_WORDS uses actual provider words, existing aligned numeric table
checks, observed long dark rule barriers, displayed-unit thresholds and explicit
source-token ownership. One PAGE call; no cell OCR, model downloads or default changes.
Rejected source lines remain unchanged; accepted table words leave contiguous unowned
source words available as ordinary output. Optional cells describe text envelopes.
No inferred merges, empty/multiline cells or continuation links.

Owned controls cover one-call extraction, escaped HTML, multiword cells, unowned
text preservation from a spanning source line, numeric rejection, painted-rule rejection,
line-only default behavior, missing capability, raw/table/cell limits, cancellation,
and invariant cell coordinates at72/144/288DPI. Explicit installed English control
produces six exact table cells and outside prose, retaining one source image.
Full Java21/25 verify797 tests each, zero failures/errors/skips, strict Javadocs,
eight installed-engine families, pinned formatter and12 reference guards pass.

Same20 pinned PubTabNet train images, original whole-image embedding at150DPI,
render300DPI and uniform SINGLE_BLOCK PAGE mode: ALIGNED_WORDS recognizes zero
tables;724/2070 reference tokens matched,1751 observed. Actual Java21/25 corpus
results identical. This does not establish broader table recognition. Corpus/source
terms remain in ../pdf-pubtabnet; raw images/labels/transcriptions are not redistributed.
Reports /tmp/docweave-word-tables-final-java21.json and java25.json.

Supplemental Java25 RULED/AUTO300DPI replay has identical complete measurements to
prior page-segmentation baseline, including69-anchor merged topology/center gate.
Existing historical reports/goldens/defaults are preserved. Companion NONE/SINGLE_BLOCK
line-only profile has identical measurements to ALIGNED_WORDS for all20 rejected
examples, so no observed text loss on this set; this is not a general losslessness proof.

Official upstream refresh09:46:42UTC still finds release/main0.27.28 six commits
beyond historical0.27.27. Online freshness assertion intentionally reports this drift;
offline full verification passes. No reference repin or full upstream parity claim.
The remaining broad scan/physical/borderless and completion gates stay open (~87%).
