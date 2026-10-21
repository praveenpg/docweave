# Inline Unicode append allocation comparison

Measured2026-10-16, Corretto25.0.4+7-LTS Linux amd64 G1 -Xms512m -Xmx512m.
Baseline5084954 rebuilt in isolated /tmp/docweave-inline-baseline with the same JDK/Maven cache.
Candidate copies original UTF-16 slices in HtmlInlineText instead of appendCodePoint. Other native
paths are unchanged; the unrelated user POM formatting edit remains excluded from commits.

before/after-1/2/3.json are three alternating fresh-JVM trials using the original pinned inputs in
../nested-table-memory/inputs, ten warmups/calls, unchanged ProfileNestedTableMemory.java. Runtime
JARs, input manifest and profiler hashes are retained. summary.json records median/range of trial
means; descriptor payload units match across builds. Probe output/warning/child-count invariants pass.

isolated-before/after-1/2/3.txt use MeasureHtmlInlineAllocation.java (source hash in summary), compiled
onto the normal application classpath to access the hidden renderer without adding public APIs.
They exclude input/TextNode construction and include renderer/output construction, twenty warmups
and twenty calls per case. ASCII/BMP/supplementary units are repeated4096times. All three trials
record196944/401752/590168bytes before,196944/401752/393560after. This isolates the append change
from loading, parsing, table capture and emission; no JMH/throughput/retained-heap claim is made.

See ../../nested-table-memory.md for full results and reproduction. Raw values are current-thread
allocated bytes; small DOCX control changes are not attributed to the HTML-only optimization.
