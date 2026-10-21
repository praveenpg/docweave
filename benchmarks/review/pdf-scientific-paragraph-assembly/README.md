# Explicit logical assembly on original scientific PDFs

This development check preserves all eight prior profiles, all four unchanged original
PDF/JATS pairs and the strict ASCII-whitespace scoring rule. It adds one explicit
SOURCE_PARAGRAPHS_ASSEMBLED profile: source-soft-hyphen page-fragment extraction followed
by the public PdfParagraphAssembler. No cross-page hyphen removal, Unicode/space repair,
header deletion, easier reference selection or model execution occurs. These sources were
already observed during prototyping; this is not a new held-out acceptance set.

The assembler requires complete compatible fragments bound to the original PDF bytes and
source paragraph ordinal. It joins text with one space at the first fragment, retains all
intervening elements in relative order and keeps complete original frames in part provenance.
It is a separate logical projection; normal page-level extraction remains unchanged.

| Source | Page-fragment exact paragraphs | Assembled exact paragraphs | Order pairs before → after |
| --- | --- | --- | --- |
| recent PLOS |6/9|7/9|15/36 →21/36|
| next PLOS |10/12|12/12|45/66 →66/66|
| eLife |0/4|0/4|0/6 →0/6|
| newer PLOS |0/3|0/3|0/3 →0/3|

The zero-match sources and every missing paragraph/pair remain in the denominator:
28 paragraphs and111 pairs per profile. A document's selected paragraphs matching does not
establish full-document accuracy, physical reading order, table fidelity or PDF completion.

Reproduce with PdfScientificReadingOrderTest using this manifest, verified original cache and
a fresh report path. VerifyAssemblyReports.java requires all36 rows equal across actual Java21
and25, and all32 prior rows (including candidate hashes) unchanged against the existing
presentation-policy report. The committed reports contain numeric outcomes/hashes, not source
text. Downloaded publisher originals stay outside Git.

Validation: full clean Maven verify passes1,026 tests on each actual JDK21/25, with no
failures/errors/skips and strict public Javadocs. Focused final21 controls also cover the
same-filename/different-PDF-byte rejection added during review. Strict private doclint passes
on both JDKs for new API/implementation/test classes, touched grouping/scientific controls and
the receipt verifier. An initial documentation-only invocation accidentally included dependency
source jars; rerunning against binary dependencies fixed that tool classpath without code or
dependency changes. Owned tests cover retained unrelated/duplicate-ID elements, supplementary
code-point offsets, malformed/incomplete/reordered groups, bounded resources, interruption,
virtual-thread reuse and actual two-page source integration.
