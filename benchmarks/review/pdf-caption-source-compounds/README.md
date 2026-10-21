# Existing source-paragraph word-wrap composition

This fixed uniform trial combines the existing edge-aligned caption policy with
explicit source-owned paragraphs, comparing `PRESERVE` and `SOURCE_LINE_WRAPS`.
All five now-observed original scientific PDFs and21 publisher captions remain;
each policy executes all15 case/layout profiles,30 total. The manifest and two
factory-composition patches were recorded before extraction. No production code,
reference projection, normalization, score or per-source routing changes.

The Scientific Reports column result remains4/5 under both policies; line profiles
remain0/5 complete bodies. All six source/layout/policy extractions succeed.
The existing source-paragraph option therefore does not close the remaining caption
gap on this source. Reports for all other originals are retained as well; no winner
is selected per document and this result does not relabel the frozen transfer.

The fifth caption's unchanged native text first differs from the publisher body at
UTF16 offset205: a generated space after a retained physical hyphen. Reference
continues with codepoint119, native text with32. Read-only diagnostic localization
uses one unique fixed32-character source prefix and exact full-text hash guard;
it neither edits the text nor awards score credit. Four other captions are exact.
Source contexts remain private and are not redistributed. This is localization,
not a proof that every later character or whole source is correct.

Reconstruct the edge-aligned three-case and one-case scorers, then apply the two
recorded factory-composition patches. Compile them into separate class directories
using Java21 and the existing reactor/runtime classpath. Run all original manifests
and caches with headless AWT and fresh reports, setting
`docweave.caption.compound.wraps=false` and `true`; both transfer scorers also use
`docweave.caption.transfer.titles=true`. Defaults for page ownership and inline
scripts remain unchanged. Both standalone scorers pass strict private Javadocs.
This is evaluation-only work; the preceding full Java21 checkpoint remains1,165
passing tests. Java25 and publication remain deferred.
