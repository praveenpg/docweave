# Complex DOCX omission-warning audit

All six omission warnings in the two affected complex-corpus inputs have valid unsupported/source
causes. This audit retains them and strengthens their regression contracts; production parsing,
public APIs, reference pins and measured original evidence are unchanged.

| Input | Source clue | Omission warning count per mode | Outcome |
| --- | --- | ---: | --- |
| drawing.docx |5bare VML rectangles, each with Office hr="t", no text/alt/title/child payload |5 |Visual horizontal rules remain unsupported; no image/text/page-break substitute |
| table-auto-nested.docx |Explicit TableGrid reference has no declared table-style definition |1 |Unresolved style diagnostic retained; default TableNormal is not substituted |

Eight nested-table flattening diagnostics also remain (7+1); they are separate from the six
omissions. Both stored-part modes retain180accepted paragraph ownership checks,8preserved nested
descriptors, unchanged root elements and matching warnings. The other two corpus samples have no
omission warnings and were not included in this focused audit.

[manifest.json](manifest.json) copies the exact selected source contracts and records the parent
manifest SHA-256; the original four-source corpus manifest/reports remain untouched. New
expected_warnings pins each diagnostic code/message pair as a multiset, including duplicates.
A matching code with the wrong message/count fails. [review.json](review.json) records current
source clues, actual detailed warnings, ownership/preservation results and source/runtime hashes.
No external binaries/raw XML/full extracted text are committed; inputs remain fetch-only.

The independent JDK source reader counts simple stored pictures and direct table-style references.
It does not select revision/AlternateContent branches, resolve entire style chains or render lines.
Source-count correspondence is supported by owned parser reproductions: adding a textless rule
preserves surrounding text/IDs with a warning; rule-only paragraphs emit no invented element;
explicit missing styles retain the unresolved marker/warning even when a default table style exists.
Additional negative controls reject descriptions/child payloads as bare rectangles, wrong-type styles,
changed warning causes/counts, malformed expectations and incorrect source evidence.

These diagnostics do not establish visual fidelity or recover vector geometry. There was no fresh
Unstructured execution, reference repin, Word collection or production behavior change. No VM is needed.
Next target a genuine supported-format gap, such as an individually documented DOCX numbering format,
using independent specification/examples and owned controls; do not suppress valid warnings to improve counts.

## Repeat offline

Use exact downloaded inputs from the parent corpus under their manifest basenames. From the root
with Java25 and a fresh output path:

```sh
mvn -B -ntp -pl docweave-validation -am test \
  -Ddocweave.corpus.manifest=benchmarks/review/docx-omission-audit/manifest.json \
  -Ddocweave.corpus.cache=/tmp/docweave-complex-corpus \
  -Ddocweave.corpus.report=/tmp/fresh-omission-audit.json
```

Default Maven/private CI uses owned controls without fetching external binaries. External evidence
is a local pinned replay, not a claim that hosted CI reruns the external source files every time.
