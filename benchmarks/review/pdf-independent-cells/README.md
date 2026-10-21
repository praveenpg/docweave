# Published native table-cell oracle

Fetch-only inputs are pinned by immutable Tabula commit, byte count, SHA-256 and Git blob in
manifest.json. The ICDAR2013 structure XML supplies cell positions/content independently of
DocWeave. The repository license is not a blanket license for embedded third-party documents;
no PDF, source XML, full transcription or preview image is redistributed here.

Selected cases: eu-010 page1 (11x2, wrapped header), us-038 page2 (8x2, wrapped header and nested
outer rules), eu-011 page3 (5x6, horizontal-rule layout, no wrapped cells). Page numbers are the
published physical page numbers, not printed page labels. Visual inspection of local previews
confirmed these layout categories; this is not a new human-approved transcription or accuracy study.

PdfIndependentCellsTest verifies all six pinned inputs before parsing. XML external access/DOCTYPE
is disabled. It reads published row/column bases (0 or1), rejects spans/duplicate/missing slots for
this unmerged subset, then partitions the complete original PDF in RULED_ONLY and
RULED_AND_MULTILINE_ALIGNED_TEXT. No annotation crop, rasterization, OCR, rule removal,
fixture-specific correction or synthetic re-layout is used.

For tables on the annotated physical page it records row widths, expected shape, the largest count
of exact whitespace-normalized cell texts at their published row/column positions, and complete
shape+text matches. It does not shift body rows to forgive missing headers. Extra unrelated tables
on the same page can appear in diagnostics; no location-overlap oracle is asserted. Separate
labels_present_in_page_text is a substring diagnostic and cannot prove cell ownership, completeness
or reading order; repeated labels may count more than once. No text/cell-box accuracy claim follows.
Misses are diagnostic results, not a failing expectation silently converted to a candidate golden.

Run with paired properties docweave.pdf.independent.cells.cache (directory with all six manifest
files) and docweave.pdf.independent.cells.report (fresh JSON filename, CREATE_NEW). Without both
properties no external corpus runs. Normal CI validates the parser/normalization guard, with no
automatic downloads or engine execution. Java21/25 local reports and fingerprints are retained here.

These cases expand independent validation, but establish no positive borderless multiline support.
The narrow owned wrapped-first-column policy remains unchanged. Investigate published wrapped-header
and rule-topology failures before widening recognition or claiming full table reconstruction.

## Observed local results

Both Java21 and25 reports have identical corpus results; no complete published table was reconstructed. eu-010: default no Table; aligned/multiline mode emits10x2 body-only Table
instead of11x2, leaving the wrapped header outside. All22 cell-label strings appear in page text.
us-038: neither mode emits a Table;15/16 label strings appear contiguously in page text. That
substring count does not establish missing glyphs: narrative can interleave between wrapped lines.
eu-011: neither mode emits a Table;30/30 label strings appear in page text. Positional table matches
are0 in all six case/mode evaluations; body rows are deliberately not shifted to forgive omissions.

The next implementation should address complete table/header recognition with these fixed published
expectations and new owned controls. Causes of missed ruled topology have not yet been traced to
specific source painting operators; visual rules alone do not establish detector input geometry.
