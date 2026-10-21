# Opt-in horizontal-rule native text tables

RULED_AND_HORIZONTAL_TEXT runs a hidden HorizontalPdfTableDetector after closed ruled tables
own their glyphs. Existing modes, defaults and constructors remain unchanged. No aligned/double
normalization or scanned/OCR inference is implicitly enabled. Shared PdfGlyphLines is the unchanged
native glyph-to-line algorithm extracted from PdfPageLayout, used by both ordinary text and cells.

The narrow strategy considers only three consecutive horizontal rule groups (ordinates within0.5).
Top and header separator each need2–32 sequential source segments of length>=12 units, with joins
within1.5 units and corresponding inferred boundaries agreeing within1 unit. Full overlapping
alternatives/duplicates are rejected. Segmentation supplies column evidence independently of text
gap splitting; header Portugal/Greece in the published PDF otherwise share an ordinary run.
Header height is8–40 units; body envelope height16–800. The next lower rule must match outer
extents within2 units and cover>=85% of the span. Lower support can have gaps and is not claimed as
a complete cell border. No virtual rule-centerline cell boxes are emitted.

Every intersecting native glyph must be upright in the displayed frame, fully within the envelope,
clear of rules/column boundaries by0.5 units, and assigned once to one complete cell. One header
baseline and at least two body baselines are required. Each cell must form one native text run,
be nonempty and<=80 code points. Header labels contain letters; at least one entire body column
matches the existing ASCII numeric syntax. Body pitch is[max(8,1.2*first-glyph height),40], varying
by at most max(2,15%); header-to-body gap independently lies in[8,min(40,2*body pitch)]. Other
vertical/diagonal/interior strokes, partial image intersections, ragged rows, crossing glyphs and
non-numeric tables reject the candidate. Overlapping candidate envelopes reject both. No sliding
search through rejected glyph rows is used.

A raster whose stored placement fully encloses the rule envelope is allowed and remains a separate
Image. The published PDF has decorative raster strips, with clipping visibility not resolved by
stored geometry. This is not a semantic background classifier; overpaint/clipping/photo interpretation
remains unsupported and can cause false positives. Native source content is never rasterized or
removed, and no OCR engine executes. Unsupported/rejected cases retain ordinary text/images.

Accepted source Tables use method horizontal_rule_text_heuristic and evidence policy
horizontal_segmented_numeric_v1 (rows/columns/numeric_columns). All cells emit escaped td strings,
without type coercion, semantic headers or empty/merged-cell inference. Optional pdf_table_cells
uses text_bounds; parent Table bounds are the text union. These are not painted vertical borders.
No ruled-table continuation signature is attached. Existing JSON/chunk/provenance policies apply.
Same TableLimits and2M shared native table-work/cancellation budget apply; no parser/heap sandbox
or general layout/author-intent guarantee is claimed.

Pinned original whole ICDAR eu-011 PDF plus published30-cell XML expectations are reused from
../pdf-independent-cells/manifest.json. New mode gates one complete5x6/30 positional text matches;
other modes keep their prior result. eu-010 remains11x2/22 matches; explicit double mode still gates
us-0388x2/16. No oracle crops, raster derivatives, source edits, row shifts, text corrections,
historical report/golden rewrites or third-party PDF/full transcription redistribution. This is a
narrow independent horizontal-rule success, not arbitrary borderless/physical-scan accuracy.

Four owned tests cover exact text/HTML/zeros/numeric punctuation,12 text-bound cell anchors,
retained ordinary text,4 concurrent calls, option copies, table budgets; unsegmented/shifted/ragged/
uneven/all-text/vertical/intruding/crossing/short/lower-gap/duplicate/partial-image fallbacks, rotated
fallback, enclosing-image retention, shared work ceiling and cancellation. Initial external rejection
identified enclosing rasters and distinct header spacing; generic policy above was tested without
changing published expectations. Java21/25 results and fingerprints saved alongside this file.

Official upstream observation is separate; historical0.27.27 reference remains explicit with known
six-commit drift to0.27.28. No automatic repin or Unstructured PDF execution/parity claim. Hosted CI
runs owned controls without downloaded corpus/actual OCR; local corpus properties remain explicit.
