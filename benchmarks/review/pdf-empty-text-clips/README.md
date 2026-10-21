# Explicit empty-inherited-clip native text filtering

`PdfOptions.TextRendering.NONZERO_ALPHA_NONEMPTY_CLIP_PAINTED_MODES` retains the existing
painting-mode/positive-alpha conditions and additionally rejects source positions when
the inherited form/page clipping area is empty. It is opt-in; `ALL`, `PAINTED_MODES` and
`NONZERO_ALPHA_PAINTED_MODES` preserve their existing behavior and provenance labels.

The check follows the source-position budget and precedes PDFBox duplicate suppression
and marked-content ownership. An invisible first copy cannot suppress a later visible
copy. Empty selection can make a page eligible for **caller-supplied** OCR, whose output
keeps OCR metadata and no native text-policy label. No OCR engine is selected or executed
automatically. The new native provenance label is
`nonzero_alpha_nonempty_clip_painted_modes_v1`.

This is a narrow inherited-state filter: it does not test individual glyph containment
inside a nonempty clip, explicit path clipping, partial visibility, soft masks, overpainting
or optional-content visibility. A nonempty clip wholly disjoint from a glyph still leaves
that glyph eligible. Native source selection is not a rendered-visibility guarantee.

## Owned controls and before/after evidence

`PdfEmptyTextClipTest` proves12 exact rendered no-hidden-form RGB comparisons:
zero-area, outside-crop and nested-zero inherited forms, four page rotations, crop and
UserUnit2. Every previous policy retains the hidden source text; the new policy preserves
only the outer visible word, with its exact complete element ID/geometry/metadata relative
to the ordinary no-form control. Additional controls retain visible forms and intentionally
retain glyphs outside a nonempty clip, exclude zero-alpha paint, preserve a visible duplicate,
charge hidden glyphs before input limits, and invoke only the explicit controlled OCR stub
when all native text is excluded. Two further no-hidden-form RGB comparisons cover the
transparent and nonempty-disjoint controls.

The pre-predicate red run adds the enum/provenance but no filtering: it passes the first
pixel proof, then fails with Hidden+Visible instead of Visible; OCR remains uncalled rather
than receiving its expected single call. These failures are retained by log hashes. After
the predicate,14 focused text rendering/alpha/clip tests pass. Four changed files pass
private strict Java21 Javadocs; full Java21 verify passes **1,208 tests**, zero failures,
errors or skips, including public docs and standalone source compilation.

All15 scientific complete-element/ID/text/metadata/warning fingerprints remain recursively
exact. The complete11-page NVIDIA replay retains all39 case/profile observations, the same
frozen8c4e990 scorer, source/reference pins, all110 reference rows per profile and unchanged
scores. Existing native policies are the measured regression population; this does not
claim independent-source accuracy gains for the new opt-in policy. No Java25, further real
OCR, model, dependency, release or visibility change is made.

Reproduce owned tests with `PdfEmptyTextClipTest,PdfTextAlphaTest,PdfTextRenderingTest`.
Original regression source identities, tools and instructions remain in
`../pdf-financial-aligned-native/` and its referenced evidence; the report `.results` trees
are exact relative to that baseline. Raw independent documents stay outside Git.
