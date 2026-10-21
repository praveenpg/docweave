# Header span proposal audit

This diagnostic tests whether area-based span alignment explains the weak education
header result. It makes no production change and does not claim a quality gain.
The frozen manifest retains the original reference and model identities. Java 21
captured pages 1 and 3 of the unchanged original us-002 PDF, with the existing
150 DPI, four-thread, KEEP_HIGHER_SCORE configuration. Only these pages were
rendered for this diagnostic; this is not another whole-document quality run.

The committed TSVs contain numeric geometry only. Objects are class-selected,
thresholded positive predictions, not raw logits. Anchor coordinates share the
same detector crop frame. Recover each atomic boundary from an anchor's start
and end indices (`row + row_span`, `column + column_span`); both grids have all
boundaries represented. For every class 4 or 5 object and atomic cell compute
horizontal overlap / cell width and vertical overlap / cell height. Compare:

- Current selection: product of these fractions > 0.5.
- Diagnostic alternative: each fraction >= 0.5 independently.

`proposals.json` records every proposal, both cell selections, and capture hashes.
The alternative isolates one aspect of Microsoft's pinned postprocessor; it does
not implement or claim parity with its header restrictions, suppression or other
alignment behavior.

## Findings

All 11 proposals on page 1 and all 9 proposals on page 3 select identical cells
under both rules. Therefore separate-axis overlap cannot repair these captures.
Page 1 has six class-5 proposals: three span the first three rows in columns 0–2,
one spans columns 3–7 in row 0, one spans rows 1–2 in column 7, and the remaining
lower-score proposal spans columns 1–2 in row 0. That last proposal overlaps the
higher-score column spans and is discarded by the configured conflict policy.
There is no thresholded proposal joining rows 1–2 in columns 3–6. This is absence
from retained predictions, not proof of absence from all raw model queries.

No thresholds, labels, row counts, spans or text ownership rules were changed.
The earlier independent results remain 2/214 and 208/215; they were not rescored
here. The missing merges cannot be recovered merely by retaining a discarded
non-conflicting proposal or changing the overlap calculation tested above.

## Remaining investigation

Native glyph ownership has not yet been correlated with these missing spans.
Before attempting a text-assisted repair, compare wrapped header cells against
legitimate stacked subheaders, preserve body-row and crossing-span controls, and
validate on all fixed regions. Header role alone and baseline gap alone have
already proved insufficient. Avoid a global row deletion or case-specific repair.
