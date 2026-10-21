# Fixed structure-score cutoff diagnostic

Reducing the structure-class score cutoff from0.5 to0.25 does **not** improve the
strict NVIDIA statement score. Both frozen detector proposal sets retain0/110 exact
publisher-reference rows at either cutoff. This bounds one configuration hypothesis;
it does not establish that all possible thresholds or structure models fail.

| Detector proposals | Structure cutoff | Associated regions | Observed statement rows | Strict exact rows |
| --- | ---: | ---: | ---: | ---: |
| TATR | 0.5 | 3/6 | 66 | 0/110 |
| TATR | 0.25 | 3/6 | 65 | 0/110 |
| JVM YOLOX | 0.5 | 6/6 | 90 | 0/110 |
| JVM YOLOX | 0.25 | 6/6 | 90 | 0/110 |

More low-score model objects do not imply more correct rows. Across all11 original
pages, TATR's total raw model-table rows rise133 to139 while the rows emitted in the
six selected statement associations fall66 to65. Those totals describe different
populations and are not recall/accuracy measures. YOLOX's whole-document model-row
total remains159. No cutoff is selected per page, no best-of result is reported and
production defaults are unchanged.

## Fixed protocol and integrity

The study manifest was frozen before either new structure run. Both detectors use
the unchanged proposal TSVs, original150DPI full-page PNGs and pinned structure graph
from [the preceding experiment](../pdf-nvidia-proposal-structures/README.md). All11
original pages run under both cutoffs and both proposal sets:44 page/profile/cutoff
observations, including pages outside the scored statements. All44 structure outcomes
are successful. Both use the existing bounded crop planner,30-second per-profile
budget, graph/query/pixel/table limits and KEEP_HIGHER_SCORE merge policy. There are
no new source downloads, dependencies, models, OCR or reference-driven crops.

All22 default0.5 table/anchor/outcome captures reproduce the original pinned files
byte-for-byte. All proposal identities and planned crop files match at both cutoffs.
After structure capture, each separate native replay protocol pins every numeric
layout before processing the complete original11-page PDF once per detector profile.
Rendered PNG hashes must match before a replay callback returns the captured layout.
Header evidence remains unspecified in this legacy TSV replay format.

The unchanged JVM row scorer retains all28 income,33 balance and49 cash-flow
reference rows per detector/cutoff, all six source-page regions and unsuccessful
matches. It neither deduplicates repeated headers nor aligns row strings. Every
native replay succeeds. The0.5 replay's complete results and document observations
are exactly equal to the earlier experiment; no historical report is overwritten.
Raw PDF/HTML/renders, transcriptions and model files remain outside Git. Reports
contain numeric geometry/counts and hashes only. This is development-source diagnosis,
not independent held-out acceptance or a DocWeave/Unstructured ranking.

## Reproduction

Compile CaptureDetectorStructures.java and ReplayNativeLayoutCaptures.java with
Java21 against current module classes plus the validation test/runtime classpath.
Recreate and verify the original cached page rasters/proposals as documented in the
preceding experiment. For each uniform score, use a fresh capture directory:

```sh
java -Djava.awt.headless=true --enable-native-access=ALL-UNNAMED \
  -cp "$audit_classes:$runtime_classpath" dev.docweave.layout.CaptureDetectorStructures \
  /path/to/frozen-page-captures /path/to/frozen-detector-proposals \
  /path/to/fresh-structure-captures /path/to/structure.onnx \
  ff1d19a18cb5f041c1692ff681f891a5180f9430d68a71d2cf7a0a2e8e55b990 0.25
```

The optional final argument selects only the existing OnnxTableOptions class-score
cutoff; omitting it preserves0.5. Invalid scores reject before output creation or
model initialization. The numeric policy.tsv records graph identity, cutoff and merge
policy. Reproduce each replay with its corresponding frozen replay-manifest.json,
original source cache, captured structures and fresh report path. For changed capture
bytes create a new protocol; do not silently repin this experiment.

Compile VerifyScoreStudy.java with the existing VerifyReplay.java and
VerifyMultiplePageRows.java. Invoke it with this study directory, original structure
captures, fresh0.5 captures, fresh0.25 captures, verifier class directory and fresh
receipt. It rechecks frozen tool/source-protocol identities,44 numeric observations,
22 byte-identical controls, proposals/crops and the complete native case/region
populations. It invokes the existing Java21 replay verifier for both cutoffs and
writes separate receipts. Current reports and receipts are retained here.

Validation: actual Java21 graph inference, whole-original native replay, strict
private Javadocs, compiler, formatter and diff check pass. NaN score rejection was
checked before any output/model allocation. Production remains at the preceding
1,125-test verified checkpoint; no production code changed and Java25 is deferred.
The structural row/header gap remains on the return queue. Further blind cutoff
tuning is deferred in favor of other unresolved PDF quality work.
