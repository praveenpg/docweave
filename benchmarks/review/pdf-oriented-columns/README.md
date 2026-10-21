# Explicit oriented lines with upright compound-column paragraphs

`TextLayout.ORIENTED_COLUMN_PARAGRAPHS_COMPOUND_WRAPS` composes the existing
projected arbitrary-angle line and compound-aware upright column strategies.
It preserves fixed geometry/limits and all source characters. Nonzero angles remain
structural barriers, including fractional rotations that an integer conversion would
otherwise incorrectly classify as upright. Historical modes keep their prior contracts.
No-angled-source output is exactly equal to the historical compound-column strategy.
Ordinal-derived IDs may change when earlier ordinary lines group; projected source
text, type, geometry and complete metadata remain unchanged. No semantic order claim.

Five owned Java methods cover public native/source-tagged factories, two complete
columns, retained physical-hyphen compound interpretation, exact upright-only regression,
fractional-angle barriers, twelve angle/displayed-quarter-turn frames with crop/UserUnit,
glyph limits, cancellation and reuse. Red controls initially demonstrate the unavailable
combination and truncated fractional-angle grouping. Initial positive fixture also had
an ineligible short first line; the final owned fixture uses an eligible full-width line,
without changing production thresholds. A first full-Element comparison wrongly expected
ordinal IDs to survive grouping; final checks compare complete source text/type/metadata,
with deterministic post-cancellation element equality checked separately.

Full clean Java21 verification: **1,224 tests**, zero failures/errors/skips, strict public
docs and standalone source compilation. Six changed Java files pass private doclint;
pinned formatting and whitespace checks pass. Java25 remains deferred.
All15 original scientific fingerprints and all39 frozen-scorer NVIDIA observations
remain exactly equal to the `pdf-binary-buffer` baseline. NVIDIA strict0/110 stays open.

The separately frozen observed magazine case retains all28 pages/106 independent image
references and every failed historical profile. The combined mode completes extraction,
like the existing ORIENTED_LINES baseline, while historical column profiles still fail.
It emits173 grouped paragraphs,50 column-marked elements and1 compound paragraph,
retaining all13 projected source lines with exact type/text/metadata multiset equality.
These are implementation observations, not independently validated reading-order scores.
Both supported modes predict zero numbered figure captions. Both retain the same image
placement discrepancies:94 emitted,90 matched,16 missing references and4 unmatched output.
No references, tolerance, mask population or failed extraction was removed to improve scores.

The existing independently reviewed106-image source reference is copied before candidate
inference with a separately declared fifth profile; source identity and selection remain.
No new raw PDF/raster/model data or dependency is committed. Reproduce with the JVM
`ScoreFigureAssociations` tool and `reference.json`, exact original PDF and fresh output.
Old caption-negative captures/scorer pins remain historical; use tool source at `a276fe0`
for their original source identity. No beta publication, tag or visibility change.
