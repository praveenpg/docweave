# DOCX conditional-style corpus audit

This review separates **unchanged external samples** from **locally instrumented derivatives**.
It is automated XML/metamorphic evidence, not independent human review, a Word-rendering oracle,
or upstream parser parity. No production library changes were needed for this review.

The historical Python snapshot below covers five originals with 14 tables. The
[current Java audit](../../review/java-style-diagnostics/README.md) reruns three cached samples
and uses the corrected version 2 direct-body contract described under Repeat offline.

The five originals contain 14 tables:

| Original | Source | Tables | Stored conditional definitions | Semantic conditional definitions |
| --- | --- | ---: | ---: | ---: |
| North Lanarkshire report | NRS | 2 | 0 | 0 |
| SQA report | NRS | 3 | 0 | 0 |
| TestTableColumns.docx | Apache POI test data | 2 | 0 | 0 |
| TestTableCellAlign.docx | Apache POI test data | 1 | 0 | 0 |
| table-alignment.docx | Apache POI test data | 6 | 6 | 0 |

“Semantic” means a conditional definition contains paragraph `numPr` or `outlineLvl`; the six
other definitions are visual formatting. Definitions can be unused; their presence does not prove
that a region was applied. All five originals parsed without warnings. Package Application metadata
reports Microsoft Office Word, but that is self-reported metadata, not proof of authorship or rendering.
The two published reports share a template family; the three externally maintained test samples do
not make this representative of arbitrary Word documents.

## Provenance and storage

[manifest.json](manifest.json) pins filenames, sizes, SHA-256, URLs, recorded notices and review status.
Apache POI sources are pinned to commit `094968cfc3d48224db08f0b7f0a6fc341b035114`
(the dereferenced `REL_5_5_1` tag): [test data](https://github.com/apache/poi/tree/094968cfc3d48224db08f0b7f0a6fc341b035114/test-data/document),
[repository LICENSE](https://github.com/apache/poi/blob/094968cfc3d48224db08f0b7f0a6fc341b035114/legal/LICENSE)
and [repository NOTICE](https://github.com/apache/poi/blob/094968cfc3d48224db08f0b7f0a6fc341b035114/legal/NOTICE).
The recorded notice identifies Apache POI and © 2003–2025 The Apache Software Foundation;
original sample authorship is not independently verified.

NRS sources retain the attribution and exclusions recorded in the [published corpus](../docx/README.md):
contains public sector information licensed under the Open Government Licence v3.0;
© Crown Copyright, National Records of Scotland. Graphic assets and stated exceptions remain excluded.
No association or endorsement is implied. Originals, derivatives, graphic assets and full extraction
outputs remain fetch-only/local; none are committed. Only aggregate observations and hashes are saved.

## Historical controlled probes

Each original is processed unchanged, then as three explicitly instrumented derivatives. The tool:

- Assigns a new owned table style with whole-table outline 9 and distinguishable conditional outlines.
- Replaces table/row-exception look and band controls and removes cached `cnfStyle` flags.
- Preserves stored text, paragraph styles, numbering, grid spans, merge structure and other ZIP parts.
- In the direct-override variant, sets table paragraphs' direct outline level to 9.

These are source-geometry probes, not evidence of the originals' conditional semantic behavior.
They deliberately replace controls/cache data and do not validate the handling of original cached
flags or independently authored conditional outline/numbering definitions.

| Probe | Observable contract |
| --- | --- |
| Enabled | Nonempty sources exercise a conditional outline below 9; text/type sequence stays unchanged. |
| Disabled | Look gates disable positional families; any table-derived outline is whole-table level 9. |
| Direct override | Direct outline wins; the table style contributes no source ranges. |

All 15 derived runs pass. Four documents provide positive text cases; TestTableColumns has empty
cells and is an explicit negative control, not a positive conditional hit. Enabled probes produce
603 table-paragraph source ranges across the four nonempty documents; disabled probes retain those
603 ranges at outline 9, and direct overrides produce none. Region membership aggregates span all
12 positional names, but membership is not a proof of exact formatting correctness or coverage of
every precedence/merge case. All variants preserve each original's ordered extracted type/text hash
and have no warnings. Existing generated Java fixtures remain the detailed rule-level regression tests.

[review.json](review.json) records original inventories, separate derivative hashes/results/checks,
manifest/tool/launcher hashes, candidate partition-source fingerprint, runtime class/JAR fingerprints
and Java version. Raw source/candidate full text is not embedded. Paragraph inventory counts are
visits, so nested-table paragraphs may be counted more than once. Text/type invariance compares Java
against its own baseline; it can miss a defect shared by baseline and derivative extraction.
There is no new upstream reference run for the Apache samples and no independent Word/PDF render.

## Repeat offline

Fetch each exact manifest `download_url` into a local cache using its manifest filename. The tool
never fetches files and rejects mismatched hashes/sizes; changing a source requires a new review.
Run the Java/JUnit validation from the repository root:

```sh
mvn -B -ntp -pl docweave-validation -am test \
  -Ddocweave.styles.manifest=benchmarks/corpus/docx-styles/manifest.json \
  -Ddocweave.styles.cache=/tmp/docweave-style-corpus \
  -Ddocweave.styles.report=/tmp/fresh-style-review.json
```

All three properties are required together. Inputs are verified before extraction; the report
requires a fresh path. No launcher, classpath file, Python, network or Windows VM is needed.
Default Maven tests use owned fixtures without external downloads. Temporary derivatives are removed.
XML parts are bounded to 16 MiB/depth 256 with DTD/external entities rejected. Derivative packages
are capped at 4096 entries/128 MiB expanded; unchanged part contents are retained, while ZIP
compression/timestamps and XML serialization are not byte-preservation claims.

Java probe version 2 preserves the instrumentation but corrects the old direct-body assertion.
The historical Python report expected direct outline 9 to shield conditional table styles.
Saved Word observations and the current policy show conditional outlines can still apply;
the new check requires a non-body conditional outline on nonblank direct-body probes.
This checks current candidate policy against itself, not independent rendering fidelity.
Paragraph styles/numbering remain intact, so unsupported source combinations can still fail a probe.
Historical reports and their original tool/launcher hashes are unchanged.
Independent review of unmodified, semantically conditional, Word-authored documents remains pending.
