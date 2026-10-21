# Source-supported clipped headers and complete span continuations

Complete a top header line only when it already crosses the inferred boundary in every
independent painted header span at one baseline. Extension reaches original glyph tops,
bounded by half the painted level pitch and twelve points. Missing spans, competing
baselines, crossing glyphs and unrelated visible ink retain the original boundary.
Wholly separate upper headings cannot trigger expansion. Original characters, source
positions and budgets remain; generic header padding is unchanged.

The existing cell builder also incorrectly rejected a second line belonging to the same
complete header span. It now permits that continuation only when the anchor span agrees
and every covered slot stays empty/covered. Conflicting, nested and overlapping new anchors
remain rejected. Owned geometry tests cover both defects independently, plus interruption,
shared work, incomplete evidence and unrelated ink.

The [initial candidate](../pdf-clipped-header-bounds/README.md) exposed this second defect
on the original PDF and was rejected. Its negative result and original protocol remain.
The new manifests freeze this corrected source identity before first scored extraction,
retaining all source/reference hashes, ROIs, ordinal scoring and previous outcomes.
Every native policy runs uniformly on each complete original. All three shaded policies
are declared change candidates on the other issuer, never selected from measured scores.
Its other policies and all earlier-period observations must remain unchanged.

The initial corrected Java21 report matches59/59 publisher rows: operations27/27 and
balance32/32. These known-source development results do not establish general PDF
accuracy, independent held-out acceptance or completion of historical/degraded OCR work.
Raw documents remain outside Git; paired verification results accompany this protocol
after validation. No source/reference repair, best-of scoring or release publication occurs.

Final actual Java21/25 reports agree across all80 case/profile observations. Each shaded
policy reaches59/59 rows on the other issuer, with no missing or extra rows. Its14 earlier
non-shaded observations remain unchanged; all30 observations per earlier period remain
unchanged, retaining114/114 and113/113 under the script-run policy. All52 scientific
observations remain unchanged. Both clean builds pass1,098 tests with zero failures,
errors or skips, strict public/private Javadocs and the pinned formatter. Final production
source hashes match the frozen protocol. The explicit official upstream drift check passes;
its release/main observation remains separate from the older measured reference runtime.
