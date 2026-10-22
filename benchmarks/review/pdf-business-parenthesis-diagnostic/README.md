# Numeric-parenthesis row presentation diagnostic

A separate diagnostic in `AuditStatementRowPresence` reproduces the previously
[classified NVIDIA spacing discrepancies](../pdf-business-miss-classification/README.md)
without changing captured text, issuer HTML, geometric association, source order, strict
scores or existing report modes. No normalized accuracy score is introduced. Independent
review of any future reference-protocol change remains open.

## Rule

Use the explicit `--section-row-parenthesis-diagnostic` mode. First consume every exact
whole-row occurrence once, using the unchanged strict comparison. For remaining references
and unconsumed observations only, compare temporary keys that remove ASCII spaces directly
before `)` in a parenthesized unsigned number. Supported numbers are ASCII digits, optional
three-digit comma groups and an optional decimal fraction. Thus `(5,491 )` and `(5,491)`
can explain one whole-row mismatch. Every other row character must agree. Each extra
association consumes one observation and records only reference/observed indices in the
new `parenthesis_spacing_diagnostic` field. Exact matches always take priority.

This is narrower than deleting all spaces or replacing every ` )`. It preserves digit,
sign, case, punctuation, Unicode whitespace, invalid comma grouping and prose differences.
It does not establish cell-boundary agreement, accounting semantics or logical row order.
A parenthesized number in prose can also match: the diagnostic recognizes syntax, not
semantic context. It must never be reported as independently reviewed cell accuracy.

## Complete replay

One full replay of the original 11-page NVIDIA document with both frozen detector capture
populations and the existing `SOURCE_NUMERIC_OR_SECTION_ROW` policy yields:

| Profile | Strict complete-row presence | Additional spacing-only associations | Strict ordinal |
| --- | --- | ---: | --- |
| YOLOX | 68/110 unchanged | 21 | 0/110 unchanged |
| TATR | 36/110 unchanged | 15 | 0/110 unchanged |

The JVM checker compares every field of all six strict baseline result records, and requires
all additional reference-index sets to equal the archived classification. Full source,
reference, frozen scorer and capture pins are checked by the existing replay infrastructure.
No OCR/model execution or downloads were needed. Historical goldens remain intact.

Self-tests cover accounting/decimal spaces, exact-first consumption, duplicate references,
exact matches excluded from diagnostics, and negatives for changed digits, missing
parentheses, prose, malformed comma grouping, Unicode spaces, case, sign and precision.
They now run in `StandaloneSourceCompatibilityTest`, which passes under Java 21. Strict
private Javadocs pass. No library code changed; the full reactor was not rerun for this
standalone diagnostic addition. Previous product verification remains 1,348 passing tests.

## Reproduction

Use a workspace containing the pinned reference manifest and frozen scorer source
identified by `benchmarks/review/pdf-tatr-fin-structure/replay-manifest.json`.
Compile `ReplayNativeLayoutCaptures`, `CompareNvidiaCellLines` and `AuditStatementRowPresence`
against the existing validation classpath. Run the same full-source replay as the section-row
baseline, replacing only `--section-row` with `--section-row-parenthesis-diagnostic` and using
a fresh report:

```sh
java -Djava.awt.headless=true -cp "$TOOLS:$FROZEN_CLASSES:$VALIDATION_CP" \
  dev.docweave.validation.AuditStatementRowPresence \
  "$REPO/benchmarks/review/pdf-tatr-fin-structure/replay-manifest.json" \
  "$SOURCE_CACHE" "$CAPTURE_ROOT" "$FRESH_REPORT" --section-row-parenthesis-diagnostic
```

Run from the frozen workspace so its relative reference/scorer paths resolve unchanged. `--self-test` runs owned controls without source files. The numeric report
here is `java21.json`; `check-source.java.txt` is the exact separate JVM checker used with
the baseline, new report and archived classification paths, respectively.

This is measurement tooling, not an extraction improvement or adoption of a new reference
policy. Table proposal coverage, split headers and the three structural YOLOX misses remain.
