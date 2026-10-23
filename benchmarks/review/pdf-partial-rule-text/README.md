# Explicit partially ruled native tables

The opt-in `RULED_AND_PARTIAL_TEXT` strategy recognizes the previously missed statistics
control as one50x7 table. It matches339/350 saved CSV positions: all315 body positions and
24/35 header positions. The11 header differences remain; source strings, CSV labels and
comparison rules are unchanged. This is a narrow native development-fixture improvement,
not general PDF/table accuracy, semantic header correctness or physical-scan validation.

## Contract and implementation

The [public policy](../../../docs/pdf-support.md#explicit-partially-ruled-tables-with-multilevel-headers)
selects a hidden implementation through an internal native-table strategy interface. Closed
ruled grids retain first ownership. Historical default/other table modes remain unchanged.
The new candidate requires a single full-width top rule, matching original segmented
separator/lower rules, bounded multilevel header, complete uniformly pitched body rows and
at least one entirely numeric body column. Partial vertical body rules must lie on supported
column boundaries. Other intersecting paint/images, rotated text, conflicting candidates,
missing body cells, glyph crossings and ambiguous same-baseline header ownership reject it.
No blank body slot, missing border, numeric correction or external engine is invented.

Header spans follow full native run extents across supported columns. They are explicit
inferences, not painted merged cells or semantic labels. Uncovered header slots are empty
anchors. Coordinates use `inferred_cell_bounds`, with column partitions and inferred row-gap
midpoints; source glyphs must fit the resulting areas. UserUnit is reported separately.
Rows/cells/characters, existing shared work budgets, interruption and per-document isolation
remain enforced. Spans are retained once in HTML/cell metadata, with no duplicate text output.

An early pilot retained the previous single-cell80-code-point cap for entire header runs and
missed this statistics table (`pilot-header-cap-java21.json`). That cap did not account for
inferred multi-column anchors. The final general contract permits80 code points per supported
spanned column, while the caller's maxCellCharacters still limits the complete anchor. A
separate authored95-character spanning header validates acceptance and caller-limit rejection.
No per-document thresholds, source-specific identities or reference crops enter production.
This is development evidence, not a held-out comparison or a claim that parameter trials were
independent. The final body cap stays80; all numeric and barrier rules remain strict.

## Owned controls

Seven test methods author independent three-column PDFs and fixed expected HTML/text/spans:

- Positive two-level spanning header, escaped text and exact once-only glyph ownership;
  outside text retained and8 concurrent virtual-thread document calls agree.
- Missing body cell, prose-only table, uneven pitch, overlapping header anchors, crossing
  glyphs, partial image, unsegmented/shifted/duplicate supporting paint, foreign body rule,
  header divider, intervening text, single header and page rotation all retain default text.
- Explicit uncovered header blanks and supported partial vertical body paint.
- Cropped380-unit page/UserUnit2 yields expected unscaled x30..270/y50 header bounds;
  pixel conversion round-trips the entire Table, including inferred cell metadata.
- Long spanning header versus caller full-anchor character limit.
- Row/column/position/character limits and immutable option copies.
- Shared page work ceiling, preserved interrupt flag and fresh-counter reuse after failure.

Full actual Java21/25 `clean verify` passes922 tests each, zero failures/errors/skips and
strict Javadocs. The final extra coordinate round-trip assertion also passes a focused21
rerun (already included in full25). All36 optional tooling guards and pinned formatter pass.
The original independent native-cell test now includes the new mode: eu-010 stays22/22;
us-038 and eu-011 remain misses for this new mode, with historical outcomes retained.

## Frozen complete original PDFs

The unchanged [input manifest](../pdf-business-cell-text/manifest.json) pins originals,
selected pages, CSV expectations and comparison ROIs. The [native protocol](../pdf-business-native/README.md)
processes each complete original document, then scores the selected page. All six policies
run uniformly, using defaults except cell-coordinate output and requested mode. No rendering,
OCR/model, reference-ROI extraction, fragment joining, source repair or label editing occurs.
The frozen maximum-parent-IoU>=0.5 association and ASCII-whitespace-only comparison remain.
All944 reference positions stay in every profile's denominator.

| Case | Earlier five modes | New partial mode | New saved CSV position matches |
| --- | --- | --- | --- |
| Financial disclosure |One88x5 table |Same |440/440 |
| Procurement |One14x11 table |Same |152/154 |
| Statistics control |No table |One50x7 table,342 anchors |339/350 |

Statistics has five inferred header rows and45 body rows, envelope IoU0.991469.
`statistics-diagnosis.json` retains all11 zero-based mismatch positions, confined to headers.
Repeated native runs can combine nearby labels into an inferred span; saved CSV header
positions are not an independent semantic span oracle. No header was corrected to improve
comparison. All14 expected empty positions match. Statistics body315/315 does not establish
correct segmentation for other documents. The two procurement spacing differences remain.
Business-only total stays592/594; including statistics rises592/944 to931/944. This combines
empty/nonempty positions and older native CSV labels, not general recognition accuracy.

Actual21/25 reports agree across all18 case/profile metrics and full local decoded captures;
JSON-byte hashes can differ due to map order. All15 earlier case/profile metrics match the
beta4 evidence. Historical reports remain untouched. Raw PDFs/CSVs/glyph strings/HTML stay
fetch-only/local. `identities.json` pins tools/source/logs/reports; no model weights enter.

## Reproduction and beta scope

Use the existing native capture/comparison commands with the optional fourth literal
`partial` to add the new sixth profile; omitting it retains the earlier five-profile launcher.

```sh
javac --release 21 -cp "$DOCWEAVE_CLASSPATH" -d /tmp/partial-native-classes \
  benchmarks/tools/CaptureNativeBusinessTables.java
java -cp "/tmp/partial-native-classes:$DOCWEAVE_CLASSPATH" \
  dev.docweave.partition.CaptureNativeBusinessTables /path/to/cache \
  benchmarks/review/pdf-business-cell-text/cases.tsv /tmp/native-partial-new partial
java -cp "docweave-validation/target/test-classes:$DOCWEAVE_CLASSPATH" \
  dev.docweave.validation.ScoreBenchmarkCaptures business-cell-text \
  benchmarks/review/pdf-business-cell-text/manifest.json /path/to/cache \
  /tmp/native-partial-new /tmp/native-partial-new.json \
  --profiles default aligned multiline double-borders horizontal partial
```

The comparator was migrated to the JVM on 2026-10-22 (`ScoreBenchmarkCaptures business-cell-text`, run from the
repository root after `mvn test-compile`); it reproduces this directory's comparison semantics; byte parity with the retired script was shown on fresh native captures.
Identity files still pin the retired Python source, which remains in repository history. See the
[migration evidence](../python-scorer-migration-2026-10-22/README.md).

Official upstream metadata still reports0.27.28/main1b02b4d189c260ed9b1aca29583203e233d853cb,
six commits beyond the historical0.27.27 pin. This is the same reviewed source delta,
including OCR agent error-cause propagation; no new partial-grid algorithm is claimed.
`upstream-status.json` preserves the live metadata; its strict pin assertion reports drift,
not a freshness/parity pass. Historical reference pins/goldens remain unchanged.

This is a scoped proposedbeta5 functionality milestone, cumulative with publishedbeta4.
The main POM stays0.1.0-SNAPSHOT. Local preparation/signing, upload and publication are
separate actions. Broad modern/scientific, real-scan, semantic reading, held-out and
calibration gates remain open. PDF85–90% remains a subjective feature-planning estimate,
not accuracy or a declaration of completion.
