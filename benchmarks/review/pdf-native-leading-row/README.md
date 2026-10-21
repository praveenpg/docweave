# Native source leading-row recovery

An explicit source-based boundary policy recovers one complete numeric body row above a
provider table in the retained NVIDIA release. The original full 11-page PDF is processed
under both unchanged frozen detector/structure profiles, without another inference call.
All 110 issuer-HTML reference rows per profile remain, including titles and headers.

| Profile | Exact complete-row occurrences before -> after / 110 | Observed associated rows before -> after | Strict ordinal matches |
| --- | --- | --- | --- |
| TATR | 35 -> 36 | 66 -> 67 | 0 -> 0 |
| YOLOX | 66 -> 67 | 90 -> 91 | 0 -> 0 |

The gain is the missing numeric row at the top of physical page 7. Its cash-flow
statement presence count improves 12/49 to 13/49 under TATR and 20/49 to 21/49 under
YOLOX. Income and balance-sheet counts remain unchanged. This is a real structured
row recovery, but the strict ordinal score remains 0/110: missing titles, different
PDF/HTML date-row presentation and other structural discrepancies remain unresolved.
The occurrence metric consumes each exact reference/output occurrence at most once;
it does not establish correct logical order, individual cell accuracy or broad PDF accuracy.
No reference-driven output repair, row shifting for scoring or denominator reduction occurs.

## Runtime policy

```java
final var layoutOptions = new PdfNativeLayoutOptions(150, 16_000_000, 1024)
        .withLeadingRow(PdfNativeLayoutOptions.LeadingRow.SOURCE_NUMERIC_ROW);
final var partitioner = PdfPartitioner.createWithNativeTablesFirst(
        PdfOptions.defaults().withTableDetection(PdfOptions.TableDetection.RULED_AND_PARTIAL_TEXT),
        layoutOptions, callerSuppliedTableLayoutProvider);
```

The default is `PROVIDER_ONLY`. Existing three- and four-argument constructors remain
available. This adds a record component, so reflective record schemas and serialized
record shapes should be reviewed on upgrade. `withCellTextJoin` retains the independently
selected leading-row policy. No OCR, model selection, download or engine execution is added.

The hidden inference helper searches exactly one original first-row height above each
validated provider table. It requires at least three uniform unmerged first-row columns,
no declared header prefix, an upright common source baseline (two-unit tolerance), one
letter-bearing label run, and a complete ASCII numeric run in every other column.
Grouping commas, decimal fractions, signs, percentages and paired accounting parentheses
are accepted; currency-separated or dash-only cells are not inferred by this policy.
Source glyphs must fit their existing column and end above the old table top.
Missing cells, multiple baselines, crossing glyphs and competing provider/native tables
retain the original table. This limited inference does not prove semantic table membership.

An accepted row uses existing column edges and a top envelope one rendered pixel above
its highest source glyph. Original physical anchors, spans, strings and provider provenance
remain; logical row indices shift by one. Table metadata identifies
`pdf_table_boundary_policy=source_leading_numeric_row_v1` and
`pdf_table_added_leading_rows=1`. Added anchors count against the same table, combined
native/provider and shared work limits. Raw provider output is validated before inference,
and the complete augmented population is validated again before source ownership changes.
Source-tagged tables, native heuristic tables and OCR output are unaffected.

## Reproduction and validation

Use the unchanged [capture manifest](../pdf-tatr-fin-structure/replay-manifest.json), original
PDF/HTML cache and all 11 captured page directories. Follow the frozen-scorer workspace
and classpath setup in the existing [presence evidence](../pdf-statement-row-presence/README.md).
Compile the current `ReplayNativeLayoutCaptures`, `CompareNvidiaCellLines` and
`AuditStatementRowPresence` Java helpers against the current Java 21 reactor classes.
Invoke `AuditStatementRowPresence REPLAY CACHE CAPTURES FRESH_REPORT` for the default
control, then the same command with a fresh report and final `--leading-row` argument.
The latter changes only the explicit boundary policy. The scorer source, original
inputs, all page rasters, table/anchor captures and reference row hashes remain guarded.

[Before](before-java21.json), [after](after-java21.json), [comparison](comparison.json) and
[receipt](receipt.json) retain every statement/profile outcome and missing/extra index.
A JVM comparator produces the numeric deltas; Python is not used to evaluate DocWeave.
Raw third-party documents and source text are not redistributed. This is known-source
development evidence, not a held-out or independently reviewed cell-boundary benchmark.

Six new owned Java tests cover actual PDF glyph ownership and default behavior, preservation
of original anchors/provenance, complete topology, ambiguous source/topology rejection,
competing ownership, constructor/copy/null contracts and added-anchor budget failure.
Full Java 21 `verify` passes 1,290 tests with zero failures/errors/skips. Private-member
Javadocs pass strict doclint. Java 25 remains deferred to final PDF acceptance.

Next: preserve this bounded gain and switch to another open gate. Do not broaden the numeric
grammar or repeat model/crop/threshold sweeps to fit this source. Missing section/header rows
require a separate source-supported approach; the business structural gate remains open.
