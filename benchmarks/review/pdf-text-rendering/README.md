# Native text rendering-mode selection

Development addition after0.1.0-beta.1, targeting the next beta once validated.
PdfOptions.TextRendering.ALL preserves defaults and all previous constructor signatures.
Explicit PAINTED_MODES excludes nonpainting rendering modes3/7 before PDFBox duplicate
suppression and native/OCR layout selection. Resource accounting precedes filtering.

This is a bounded native-text feature, not general rendered visibility, redaction or improved
scanned-table recognition. Transparency, clipping, occlusion and optional-content visibility
are deliberately not inferred. The option can discard useful hidden search/OCR layers.
No probability/confidence score or broader PDF completion is claimed.

Owned tests use all8 rendering modes, same-position hidden/painted duplicates, filtered-glyph
limits, old constructors/copy preservation, explicit OCR page selection and a clipped/
transparent painting-mode counterexample. Native policy provenance does not label OCR output.
No external documents, models or OCR engine were downloaded for these controls.

Fresh official upstream guard2026-10-18 reports latest release/main0.27.28 at
1b02b4d189c260ed9b1aca29583203e233d853cb, matching the previously reviewed revision.
It fails the strict freshness gate against historical0.27.27, not an extraction regression.
The first sandboxed attempt failed network access; the network-enabled retry produced the
saved source-hashed report. Historical reference pins/goldens remain unchanged.
See [the feature contract](../../../docs/pdf-support.md#explicit-native-text-rendering-modes).

Actual Java21 and25 full clean verify each passes905tests (six new regressions), zero
failures/errors/skips, with strict Javadocs. A consumer compiled against the beta1 full
PdfOptions constructor links on both JVMs against the same new Java21-built JARs and preserves
default equality. Standalone helper doclint passes on both, and pinned formatting passes.
Exact source/log fingerprints are in verification.json. No real OCR/model resource is selected.

The consolidated private release tool prepared a local signed0.1.0-beta.2 candidate from
the source revision in beta-candidate.json. Fresh versioned builds pass905tests/strict docs
and archive checks on each JDK; independent PDF/OCR/ONNX consumers pass on both using the
same captured Java21 artifacts. All65 signatures verify with the exported PUBLIC key.
The260-entry Central-layout ZIP is recorded by SHA256 and has not been uploaded or published.
The development reactor remains0.1.0-SNAPSHOT. No beta1 artifact or prior evidence was edited.
