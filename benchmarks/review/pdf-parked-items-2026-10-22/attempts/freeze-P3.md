# P3 attempts freeze (written after inspecting band geometry near the targets, before simulating any rule)

Targets (frozen earlier, fd6132b6): Apple p3 a label-only disclosure heading (y 685.8) and
a two-value cash-paid row (y 700.0) below the provider table (bottom 678.6); Amazon p7
a four-value diluted-shares row (y 527.3) below the table (bottom 514.3). Pinned upstream excludes all three (compatible).
Inspection: both producers paint one full-width filled band (white included) behind every table row;
Apple bands continue to 703.6 then stop; Amazon's band 515.3-531.0 holds the target.
Disclosure: this mechanism was suggested by these same documents (development evidence, not independent).

Evaluation-only simulation on every provider table of both retained captures (Apple 5, Amazon 7), using
PDFBox text positions and filled rectangles. Provider rows in points = pixels x 72/150.

## Attempt 1: painted row-band lattice
Bands = filled (fill / fill+stroke) rectangles of any colour grouped by equal top and bottom (0.5 unit),
height >= 4 units; a band is full when the union of its rectangles covers >= 90% of the table width.
Active only if >= 2 provider rows with visible text have every baseline inside a full band. Scan below
the band holding the last provider row's baseline: the next band must be full and start within 1.5
units of the previous band's bottom. A band with visible text becomes one row if every visible glyph
centre lies inside the table's horizontal extent and inside one of the last provider row's unmerged
column ranges; otherwise stop. Blank full bands are crossed. Stop at the first non-adjacent band.

## Attempt 2: column-aligned numeric continuation (no graphics)
Below the table, lines in baseline order. Accept a line when its first visible token starts inside
column 0's range and every other visible token is numeric (digits with , . ( ) % $ only) with its
centre inside a distinct value column, covering every value column that has text in the last provider
row; accept a colon-terminated label-only line only when the next line is accepted. Each accepted line's
baseline must be within 1.5 x the median provider row pitch of the previous accepted baseline (the
table's last row baseline first). Stop at the first failure.

Acceptance (each attempt): all three targets recovered as rows, zero other lines appended to any of
the 12 tables (any other appended line counts as an extra, judged privately by text), nothing removed.
A passing simulation would still need a runtime implementation, owned negatives and an independently
labelled transfer document before promotion; P3 remains optional because upstream excludes these rows.
