# Source-authored logical tables without fabricated blank-cell geometry

The original Walmart filing declares statement tables containing wholly empty source rows
and columns. The existing source-tagged path requires independent text support on every
axis before assigning empty-cell rectangles, and therefore rejects these tables. Source
inspection did not find direct table/row/cell BBox attributes supporting those empty axes.

The new caller-selected `LOGICAL_TEXT_ENVELOPE` policy preserves the authored grid while
requiring cell coordinates to be disabled. It uses only the enclosing nonempty native text
bounds for the table. Empty slots receive no rectangles. Every nonblank original glyph
center inside the envelope must belong to the table; missing, reused or ambiguous source
identities, wholly blank tables and non-upright displayed text retain whole fallback.
Existing factory behavior remains `SUPPORTED_CELL_AXES`.

Four manifests freeze original sources, issuer HTML row references, pages, ROIs, scoring,
budgets, candidate source hashes and the previous reports before first candidate extraction.
Each appends one uniform `SOURCE_TAGGED_LOGICAL_TABLES` profile. All earlier profiles remain;
no earlier profile is permitted to change. Inherited historical protocol fields remain as
provenance; `candidate_source_sha256` binds the current implementation and scorer bytes.
The Walmart 64MiB input allowance was declared in its original protocol, and is unchanged.
Every profile processes the complete original PDF, including the 59-page Walmart filing.
References and ROI labels enter only the unchanged downstream scorer.

The new Walmart profile recovers29/29 operations,40/40 balance-sheet and36/36 cash-flow
rows:105/105 exact ordinal publisher-reference rows. This is development evidence for a
source-logical table representation, not independent physical-cell geometry, general PDF
accuracy or semantic correctness. Existing native/source-tagged paths retain their0/105
result. No failed observation, blank reference denominator or original label is removed.

Owned JVM controls cover a grid with an empty middle row and column, retained outside text
and raster placement, missing/duplicate references, unclaimed interior text, wholly blank
tables, displayed rotation rejection, unsupported source structures, resource limits,
interruption, incompatible cell-coordinate requests and immutable configuration copies.
No model, OCR engine, runtime dependency, default, release or Central deployment changes.

Both clean actual Java21/25 builds pass1,105 tests with zero failures/errors/skips and
strict public Javadocs. Strict private Javadocs and the pinned formatter pass. All124 paired
business case/profile observations agree between runtimes, and all113 earlier observations
remain unchanged. All52 scientific observations remain unchanged. The new logical profile
has0 exact rows on each of the other three PDFs; it complements their existing native policies.
No policy is selected after inspecting reference matches. Candidate source hashes are
verified against final bytes. Receipts retain every source, failure and row denominator.
