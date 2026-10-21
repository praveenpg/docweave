# Existing source-tagged-table factory on the same original filing

The original PDF declares31 Table,410 TR and3,824 TD nodes. This motivates testing the
existing `PdfPartitioner.createWithTaggedTables(...)` path, but does not establish that its
source structure meets the supported contract. The manifest appends one explicit factory
profile before its first extraction, preserving all30 native observations, original105-row
references, full59-page PDF, fixed pages/ROIs and the predeclared64MiB input budget.

All33 case/profile observations agree on actual Java21/25. All30 native results remain
unchanged. The source-tagged profile also associates none of the three statement tables:
0/105 exact rows, complete-document processing succeeds, and ordinary page text remains.
No provider, OCR, source/reference repair, output selection or production tuning occurs.
Every failure and denominator stays in the reports. This checks an existing factory; it
adds no production functionality and does not establish a general accuracy ranking.

Independent source-plan inspection accepts11 formatting/header plans and rejects20 source
tables. A diagnostic copy locates19 rejections while looking for TR descendants and
encountering bare COSInteger content references; one other table declares an out-of-range
row span. These are diagnoses, not authority to ignore source content or invent empty cells.
Original reference attributes and retained glyph ownership need inspection before a fix.
Debug copies and raw original text remain outside Git.

Business reference/scoring and standalone JVM source-compatibility controls pass on both
JDKs, as do strict private Javadocs and the pinned formatter. The shared verifier checks
all33 paired results and all30 prior observations. The scorer identity is bound by the
manifest. No additional full build is claimed for this validation-only change; the last
production clean builds pass1,098 tests on both JDKs. No release/Central action occurs.
