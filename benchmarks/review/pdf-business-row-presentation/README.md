# Same-index business row presentation diagnostic

The complete original AMD PDF still scores **81/120 strict ordinal rows** against
its publisher HTML. All39 remaining same-index differences can be described by
ASCII spaces, em-dashes versus hyphens, or both. No production behavior, reference,
normalization or accuracy score changes. The complete original Walmart control
retains105/105 strict rows under the same explicit source-cell-envelope policy.

| Source | Strict exact | ASCII spaces only | Em-dash/hyphen only | Both | Other | Missing/extra |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| AMD | 81/120 | 19 | 7 | 13 | 0 | 0/0 |
| Walmart | 105/105 | 0 | 0 | 0 | 0 | 0/0 |

This is a post-hoc diagnostic of previously observed development sources, not a
prospective accuracy improvement or independent acceptance. It cannot establish
which representation is semantically correct. Removing spaces can erase meaningful
word boundaries; changing a dash can erase meaningful typography. Neither transform
is allowed in the strict scorer or used to modify extraction. Complete row indices,
strict counts and all nonexact positions remain in the numeric reports. No raw
source text, PDF, HTML or renders are redistributed.

The JVM launcher [AuditBusinessRowPresentation](../../tools/AuditBusinessRowPresentation.java)
verifies every input's byte count/SHA256 and all original reference-row hashes,
extracts the complete original once with SOURCE_TAGGED_CELL_ENVELOPE_TABLES, then
associates each frozen page/ROI by maximum parent IoU>=0.5 with stable first ties.
References never enter extraction. The original manifest's input budget applies;
there are no models, OCR, crops, row shifts, fuzzy alignment or reference repairs.
Typed whole-document failures retain complete denominators and missing positions;
unexpected errors fail the run. Separate report identities pin code, tool, original
manifests and outputs. These are HTML row-content references, not PDF physical cell
or span annotations. This launcher intentionally accepts only single-page cases.

Build with Java21 against the existing runtime classpath, then invoke:

```sh
java -Djava.awt.headless=true -cp "$audit_classes:$runtime_classpath" \
  dev.docweave.validation.AuditBusinessRowPresentation \
  benchmarks/review/pdf-unseen-business-amd/cell-envelopes-manifest.json \
  /path/to/verified/amd-cache /path/to/fresh-amd-report.json

java -Djava.awt.headless=true -cp "$audit_classes:$runtime_classpath" \
  dev.docweave.validation.AuditBusinessRowPresentation \
  benchmarks/review/pdf-tagged-reference-tables/caption-walmart-manifest.json \
  /path/to/verified/walmart-cache /path/to/fresh-walmart-report.json
```

The launcher's `--self-test` checks category exclusivity and retains numeric, case,
leading-zero, Unicode-minus, non-ASCII-space and reordered-character negatives.
Compilation, owned controls and strict private Javadocs pass on Java21. Production
remains at the preceding1,125-test verified checkpoint; this diagnostic-only addition
does not justify repeating the full reactor or Java25 checks. Java25 remains deferred.
NVIDIA's independent structural0/110 result remains an open gap; these diagnostic
categories neither repair it nor establish overall PDF completion.
