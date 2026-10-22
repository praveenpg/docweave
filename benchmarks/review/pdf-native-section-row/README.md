# Explicit source-leading section rows

An opt-in policy recovers a label-only section row above an existing provider table
without broadening the earlier numeric-only policy. On the complete original NVIDIA
release, YOLOX exact complete-row presence improves **67 -> 68/110**, recovering the
physical page-6 section label. TATR stays **36/110**; it has no balance-sheet table
proposal on that page. Strict ordinal matching stays **0/110** for both profiles.
All eleven original pages, both frozen capture populations and all 110 issuer reference
rows per profile remain in the evaluation. No source/reference repair, new model inference,
crop tuning, denominator reduction or per-source winner selection occurs.

| Profile | Income presence | Balance presence | Cash-flow presence | Total presence |
| --- | --- | --- | --- | --- |
| TATR before -> after | 10 -> 10/28 | 13 -> 13/33 | 13 -> 13/49 | 36 -> 36/110 |
| YOLOX before -> after | 21 -> 21/28 | 25 -> 26/33 | 21 -> 21/49 | 67 -> 68/110 |

Presence consumes each complete exact row occurrence once and remains a diagnostic
alongside unchanged strict ordinal scoring. It does not establish cell accuracy, semantic
reading order, broad table accuracy or a percentage of PDF completion.

## Source-supported behavior

```java
final var options = PdfNativeLayoutOptions.defaults()
        .withLeadingRow(PdfNativeLayoutOptions.LeadingRow.SOURCE_NUMERIC_OR_SECTION_ROW);
```

Pass these options to the existing native-layout factory with a caller-supplied provider.
The default remains `PROVIDER_ONLY`. `SOURCE_NUMERIC_ROW` retains its numeric-only behavior.
The combined option adds an enum member, requiring exhaustive switches to handle it when
recompiled, without changing record components or provider interfaces.

The section candidate must be one letter-bearing colon-terminated label in the first column,
with the other leading cells empty. All original numeric-row topology, source-band and
ownership constraints remain: at least three uniform unmerged first-row columns, no declared
header prefix, one original first-row-height search band and upright source glyphs fitting
their column. Original physical anchors and provider provenance are retained; logical row
indices shift by one, and added anchors count against normal table/work limits. Raw layouts
are validated before inference and the full augmented population is validated afterward.

The first body row must support the candidate with a single label run and complete numeric
values in the remaining cells, on one common source baseline within two displayed units.
One leading `$`, `£` or `€` is permitted in those supporting cells only. Horizontally
separated currency/digit runs on that same baseline are joined; different vertical baselines
remain unsupported. The shared numeric-row pattern is unchanged. Alignment uses source
origins, including indentation-space glyphs on each selected label baseline, within two
displayed units. Body and section whitespace on unrelated baselines cannot establish alignment.

Changed table metadata identifies `source_leading_numeric_or_section_row_v1` and one added
leading row. The geometry rule does not prove semantic membership: unrelated aligned colon
headings over numeric content can remain ambiguous. Use the default to retain exact provider
bounds. No broad heading classifier, OCR routing, download or dependency is added.

## Investigation and safeguards

The [initial investigation](../pdf-native-section-row-attempt/README.md) parked a permissive
gain after a stronger safeguard lost it. The additive diagnosis there identified two
measurement defects: separately positioned currency marks were treated as multiple cells'
lines, and blank-filtered body glyphs omitted the source indentation origin. The follow-up
patch corrected those measurements. The integrated implementation additionally rejects
multiple supporting body baselines and unrelated whitespace when measuring alignment.
Original attempt reports remain historical evidence; their status describes that attempt,
not the subsequently integrated policy.

Seven new owned JVM tests use actual PDF text operators and PDFBox source-glyph extraction.
They cover indentation/currency gaps, default and numeric-only ownership, empty section cells,
original anchors/provenance, centered/prose/non-colon headings, missing/malformed numeric
values, multiline/rotated/distant/cross-column source, competing provider ownership, options
and the aggregate anchor ceiling. A structurally distinct four-column owned PDF with a pound
currency mark and 144 DPI rendering transfers the behavior from the three-column 72 DPI
control. This is an owned transfer check, not another independent publisher benchmark.

Full Java 21 `verify` passes **1,308 tests**, zero failures/errors/skips, including strict
public Javadocs and standalone source-tool compilation. Changed private methods and the
new owned suite also pass strict private doclint. The 13 focused numeric/section tests pass.
Java 25 remains deferred to final PDF acceptance. Numeric-only replay retains the complete
baseline result population unchanged. The original measured Unstructured runtime is not rerun.

## Reproduction

Use the pinned [replay manifest](../pdf-tatr-fin-structure/replay-manifest.json), original
PDF/HTML cache and all eleven capture directories. Follow the frozen scorer workspace and
classpath setup in [the existing numeric evidence](../pdf-native-leading-row/README.md).
Compile the current `ReplayNativeLayoutCaptures`, `CompareNvidiaCellLines` and
`AuditStatementRowPresence` helpers against current Java 21 reactor classes. Invoke:

```text
AuditStatementRowPresence REPLAY_MANIFEST SOURCE_CACHE CAPTURE_ROOT FRESH_REPORT --section-row
```

Repeat with `--leading-row` for the numeric-only control. Render/source/capture/reference
and frozen-scorer identities remain guarded. The unchanged JVM comparator produces
[comparison.json](comparison.json) from the [before](before-java21.json) and
[after](after-java21.json) reports. [numeric-only-java21.json](numeric-only-java21.json)
retains the existing policy's control. [receipt.json](receipt.json) binds numeric evidence
and verification logs. Raw third-party sources, transcriptions, screenshots, models and
machine-local operational details are not redistributed.
