# Explicit declared provider header-prefix continuation

REPEATED_HEADER_PREFIX_WITH_PROVIDERS adds adjacent-page links using two through
four explicitly declared unmerged header rows, capped at32 original header cells.
No baselines are split and no semantic role is inferred. This is separate from the
retained experimental native-gap topology refinement. Earlier continuation policies
retain single-row behavior; defaults remain unchanged.

The complete owned three-page source has two columns and two repeated header rows,
including a numeric year beneath a textual column label. Native glyphs, page-word
OCR and independent per-cell OCR each run with and without coordinate serialization:
six chains/eighteen table observations. NONE and the old provider policy create zero
links; the prefix policy creates two per chain(twelve total). Roots stay unmodified;
every source ID, text, HTML and other metadata equals NONE after removing only the
continuation map. Links use actual prior/root emitted IDs. Source rows are not merged.

Row/cell boundaries remain explicit in matching, including cases with equal flattened
column concatenations but differing cell boundaries. Changed years, blank/NBSP-only
header cells, numeric-only column evidence, oversized labels and missing bodies are
rejected. Two/three/four-row prefixes and exactly32 cells have positive controls;
five rows and more than32 cells are rejected. Merged/jagged prefixes supply no evidence.
All previous source-frame, recognition-family/provenance, isolation/page-edge and
work/text limits remain. Unknown/all-header declarations and nonzero corrected word/
cell OCR frames remain unlinked under the new policy.

Full Java21 mvn verify passes1,175 tests with zero failures, errors or skips. Three
new test methods extend PdfProviderContinuationTest. Strict private Javadocs and pinned
formatting pass. Final additional merged/jagged assertions run in the focused continuation
suite after the full build; no production behavior changed after that full verification.
Reproduce with PdfProviderContinuationTest/PdfTableContinuationTest or full mvn verify.
All sources/callbacks are JVM-owned, with no external model, Python evaluator or downloads.

This verifies explicit integration behavior, not new original-source recognition accuracy,
NVIDIA strict-row recovery or broad multi-page semantic table reconstruction. Existing
header/body span ownership, OCR corrections and physical-scan acceptance remain bounded.
Java25 remains deferred until PDF completion; no release/publication is performed.
