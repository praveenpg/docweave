# Synchronized native model-row experiment

All11 NVIDIA original pages and both frozen detector/structure profiles are retained.
No model is rerun, source/reference text is rewritten or production default changed.
The complete baseline inventory contains292 captured row groups. One uniform gate
admits5 candidates: multiple occupied single-column cells, equal native baseline
counts and matching baseline positions within the existing2point tolerance. It rejects
38 spanning rows,20 unequal-baseline rows,221 rows without shared multiline baselines
and8 rows with insufficient occupied columns. These are geometric categories, not
correct/incorrect row labels. All row records and empty pages remain in gate-java21.json.

A separate experiment checks original source glyphs for each candidate. Every split
requires an open common vertical gap between native glyph extents, wholly inside
every existing cell's vertical bounds. Rows crossed by an earlier row span fall back.
All5 candidates pass. New captures subdivide existing cells without moving glyphs,
column boundaries or table envelopes. Empty cells subdivide geometrically without
invented text. Existing row ordinals shift; original tables/captures remain intact.
This is an explicit geometric interpretation, not proof of logical table rows.

| Complete statement comparison | TATR baseline | TATR refined | YOLOX baseline | YOLOX refined |
| --- | ---: | ---: | ---: | ---: |
| Associated page regions |3/6|3/6|6/6|6/6|
| Observed content rows |66|67|90|93|
| Exact ordinal rows |0/110|0/110|0/110|0/110|
| Complete statements |0/3|0/3|0/3|0/3|

Both complete originals/profile replays succeed and consume all11 rendered pages.
The additional observed rows are not counts of correct rows. Strict title/header,
ordinal and text mismatches remain; no production row-refinement API is promoted.
No normalization, fuzzy matching, source-specific thresholds or reference repair
enters extraction or scoring. This is a retained partial/negative experiment.

Eight JVM gate controls cover synchronization/tolerance, wrapping, spans and empty
columns. Six source-gap controls cover an exact gap, unequal lines, overlapping glyph
extents, empty rows, insufficient occupied source columns and interruption/reuse.
Compilation, strict private Javadocs and pinned formatting pass. Product source is
unchanged since00ca0f1's1,172-test Java21 verification; no redundant reactor run or
Java25 run is claimed for this benchmark-only addition.

## Reproduction

Use the exact original PDF, all11 rasters and model captures pinned by
../pdf-nvidia-proposal-structures/replay-manifest.json. Compile the two new tools
and ReplayNativeLayoutCaptures.java against the existing Java21 validation/test
classpath. AuditSynchronizedModelRows accepts inventory path, expected SHA256 and
fresh numeric report. RefineSynchronizedModelRows accepts original PDF, original
replay manifest, raster root, capture root, gate report and fresh output directory.
Both offer --self-test. Every source/raster/capture identity is checked. The new
replay manifest pins derived numeric captures; originals are never replaced.

The historical replay pins scorer8c4e990. Current scorer source differs, so the
first replay safely stopped before extraction. Extract that exact source with git
show into an isolated working directory/classpath, verify the manifest SHA256,
and compile it there. Make the benchmark reference paths available in that directory
without modifying their bytes. Run the unchanged replay from that directory with
the isolated frozen scorer classes first. Repeat once with original captures and
once with derived captures, using fresh report paths. This preserves the original
reference parser, scorer and all110 rows. The manifest records exact tool/scorer
identities; no hash guard is removed or rewritten to accept current scoring code.

Numeric reports are distributed here; PDF, HTML, rasters and model weights are
fetch-only. Cache paths and publisher transcriptions remain outside the repository.
