# Scientific paragraph difference diagnostics

This is a JVM-only diagnostic on the same four development PDFs and publisher XML references,
not a scoring change or a new extraction heuristic. Both original default lines and explicit
source-paragraph assembly run uniformly on all four complete documents. All eight strict
result rows, candidate text hashes and original reference/order denominators are unchanged
from the pinned interim comparison on both actual Java21 and25. The current library source
is9fd30c7; no upstream extraction is repeated or silently relabelled.

## Observations

| Original source | References | Exact default lines | Exact assembly | Uniquely anchored nonexact spans: lines / assembly |
| --- | ---: | ---: | ---: | --- |
| PLOS0324609 |9|1|7|6 /2|
| PLOS0330904 |12|2|12|8 /0|
| eLife90049v1 |4|0|0|2 /2|
| PLOS0360958 |3|0|0|2 /2|

All56 paragraph/profile observations remain:22 exact,24 uniquely anchored nonexact diagnostic
spans and10 missing/ambiguous-anchor cases. The10 unlocated observations do not acquire zero
edit counts or disappear from the strict score. These counts are not document accuracy.

For the first PLOS source, the two assembly mismatches each contain exactly one inserted ASCII
space after a preserved physical hyphen, with no optimal-predecessor tie along those traces.
No hyphen deletion, source repair or whitespace folding is applied. This identifies a small,
specific spacing issue for later source/geometry review rather than changing an exact match.

For the two located eLife spans, each profile has28 diagnostic edits:21 space/NBSP substitutions
and seven insertions, including three hyphens and four spaces after hyphens. Two other eLife
paragraphs cannot be located under the fixed anchor rule. These subset operations are not an
explanation of every missing paragraph or permission to normalize source Unicode.

The newer PLOS source's two located spans differ substantially:1597 diagnostic edits for lines
and1337 for assembly. The assembly trace includes1327 insertions, seven deletions and three
substitutions, with103 visited steps having multiple optimal predecessors. Another paragraph
has missing/ambiguous anchors. These are differences inside anchor-delimited output spans;
they do not identify true PDF glyph loss, prove semantic reading order or attribute a cause.
Large/interleaved spans require further source/geometry review rather than a generic spacing fix.

## Fixed protocol and guardrails

The [protocol](protocol.json) pins the unchanged manifest, prior report and two uniform profiles
before this diagnostic extraction. Sources were already observed in development; this is not
held-out acceptance. Complete original PDF/XML bytes and all selected reference hashes are
verified before extraction. XML/reference text enters scoring only after whole-PDF extraction.
ASCII whitespace normalization is the existing strict rule; Unicode, NBSP, case, punctuation
and physical hyphens remain unchanged. The historical benchmark reports stay immutable.

A nonexact paragraph is localized only when its exact first32 and last32 Unicode code points
each occur once in the complete candidate and appear in nonoverlapping source order. Missing,
duplicate, short, reversed/overlapping or budget-exceeded observations retain explicit statuses.
No fuzzy search, best occurrence, trimming, label substitution or repaired text is used.

For a located span, a global character edit matrix is limited prospectively to16million cells.
One deterministic optimal trace prefers diagonal, then deletion, then insertion. Operation
counts partition its edit distance; NBSP/space, physical hyphens and spaces after hyphens are
reported as subsets. Visited predecessor ties do not establish uniqueness of all possible
alignments. The trace is a development diagnostic, not CER, full-text recall, causal error
labels, calibrated confidence or a fair cross-library accuracy metric. References can differ
from printed PDF presentation; independent source review is needed before production changes.

## Reproduce and verify

Five focused scientific/standalone controls pass on each actual JDK21/25, including the new
owned diagnostic controls. Strict touched-private docs, formatter and diff checks pass. See
validation.json. Production is unchanged, so the prior1,053-test clean build baseline remains
applicable; it is not reported as a new full-suite run for this diagnostic-only change.

Compile benchmarks/tools/AuditScientificTextDifferences.java at release21 against current
reactor classes and validation test/runtime dependencies. Keep current classes before any
older installed artifacts, and compile the launcher before running because it uses package-
private validation helpers. Run dev.docweave.validation.AuditScientificTextDifferences with
protocol.json, the verified scientific-reading source cache and a NEW report path on each
actual JDK. --self-test checks exact/duplicate/missing/reversed/short cases, Unicode/physical
characters, trace accounting, cell-budget preflight and unchanged strict-score failure.
The ordinary standalone compatibility test also compiles and runs these owned JVM controls.

Run VerifyTextDifferences.java with protocol, reports21/25 and a NEW numeric receipt. It requires
all eight original case/profile keys,56 ordinal observations, cross-JVM equality, unchanged
strict rows and complete edit accounting. No alignment exists for an unlocated case. Only
numeric outcomes, source/text hashes and operation/status counts are committed; publisher
transcriptions, PDFs and raw private probes remain outside Git. No production API/algorithm,
dependency, version, release or Central operation accompanies this diagnostic.
