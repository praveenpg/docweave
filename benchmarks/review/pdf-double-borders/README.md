# Opt-in native double-rule normalization

Published ICDAR us-038 page2 (pins in ../pdf-independent-cells/manifest.json) has double outside
rules and a double header separator. With ordinary ruled recognition, the thin-fill milestone
already emits a7x2 body-only table; the wrapped header remains outside. Historical reports in
../pdf-filled-rules/ preserve that result. It is not a completely absent table.

RULED_AND_DOUBLE_BORDERS explicitly enables DoublePdfRuleNormalizer before the existing native
closed-grid detector. Default RULED_ONLY, aligned and multiline-aligned modes are unchanged.
This mode does not enable aligned-text or scanned/OCR rule inference. No new dependency, public
service class or constructor change is introduced.

Near duplicates are bucketed within the existing0.5-unit tolerance without transitive drift.
Eligible pairs are isolated adjacent axes at most2.5 units apart, with source interval extents
matching within2.5 units. Triples/chains are not collapsed. Original glyph bounds and raster boxes
veto any intersecting pair band/envelope including a0.5-unit halo. Each source axis must remain
independently continuous within0.5 after eligible perpendicular junction mapping; if this fails,
the whole page normalization is abandoned. Endpoints move only inside the pair+0.5 halo and
within its source along-axis extent+0.5. Both paired axes map to their midpoint, without averaging
unrelated far-away segments. Unsupported diagonals remain unchanged. Original source segments are
never mutated. All scans use the same2M page table counter and cooperative cancellation checks.

Closed perimeter, rectangular merges, overlap/nesting rejection and TableLimits still run after
normalization. Limits refer to normalized atomic rows/columns/slots; source operators and work
remain separately bounded. Empty narrow cells can be intentional, glyph bounds are approximate,
and ignored vector art/clipping/overpaint remain ambiguous, so this is a heuristic with an explicit
warning, not author-intent recognition. Source Tables in this mode carry pdf_table_rule_policy=
double_border_v1; cell coordinates describe normalized rule-centerline boundaries, not each member
of the original double line. Continuation/JSON/chunk contracts retain their existing behavior.

Owned PdfDoubleBorderTest checks exact combined wrapped-header text, HTML,3x2 geometry/midpoints,
all page quarter turns, mode/default/copy isolation,4 concurrent partitions; occupied bands and
halo vetoes, unequal extents, triples, wide pairs, incomplete coverage, unrelated segments and
unchanged diagonals; broad nested-grid rejection; shared work ceiling and cancellation.

The pinned whole original us-038 PDF now must produce one8x2 table with16/16 positional normalized
cell-text matches in this explicit mode. The independent test gates that fixed published oracle;
no annotation crops, raster/OCR, source repair, header guessing, row shifting, numeric/case/label
correction or historical golden/report rewrite. eu-010 remains11x2/22 matches in all modes; the
partially ruled eu-011 remains diagnostic. No positive borderless, physical-scan or broad semantic
accuracy claim follows. No third-party PDF/full annotations/text or preview is redistributed.

Official upstream release/main observation and Java21/25 test/corpus/source fingerprints are
retained alongside this file. The historical0.27.27 reference remains explicit, without repinning.
Hosted defaults run owned controls, not the fetched corpus or an actual OCR engine. No VM needed.
