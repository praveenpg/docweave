# Independently maintained complex DOCX samples

Four immutable, fetch-only samples add source structures absent from the earlier official forms.
They are maintained by external projects and were not generated or altered to fit DocWeave.
They are regression fixtures, not representative published-document or independent rendering evidence.
Source/notice URLs, exact commit/blob/SHA-256/byte identities and selection limits are in
[manifest.json](manifest.json). Git blob identities were verified against each downloaded payload.
No external binaries, raw XML, full extracted text or graphic assets are committed.

| Sample | Maintainer | Stored source feature | Accepted paragraphs per mode | Other checks |
| --- | --- | --- | ---: | --- |
| drawing.docx | Apache POI |12tables,7nested; depth4 |176 |7preserved descriptors; unchanged roots/warnings |
| delins.docx | Apache POI |32insertions,4deletion containers |21 |5distinct long deleted fragments absent |
| table-auto-nested.docx | LibreOffice |2tables,1nested; depth2 |4 |1preserved descriptor; unchanged roots/warnings |
| tdf108973_foregroundTextbox.docx | LibreOffice |1main-body textbox,1nested paragraph |2 |Textbox owner and parent relationship |

Both default and include-headers/footers policies pass:203accepted paragraphs per mode with no
unmatched paragraphs, owner failures or unexpected owned elements. No Header/Footer elements are
expected for these inputs. Raw inventory counts include revision branches; accepted counts do not.
`drawing.docx` retains7flattening and5omission warnings; `table-auto-nested.docx` retains1of each.
The preservation mode retains the same warnings; their exact multiset is part of the contract.
Omission warnings are not evidence that omitted content was recovered.

## Independent checks and limits

[inventory.json](inventory.json) inventories stored XML with the bounded independent JDK reader.
[review.json](review.json) records actual Java public-API extraction, independent ownership checks,
feature checks and ordered runtime/source fingerprints. Source text is confined to memory; reports
contain structural paths, aggregate counts and hashes. No new upstream reference or Word observations
were manufactured. Passing ownership is normalized disjoint substring/order/type/parent evidence,
not exact text, glyph, table cell order, typography, layout, or arbitrary XML branch selection.

Preservation checks compare root IDs/types/text/metadata after removing only nested-table sidecars,
compare warning objects exactly and require reviewed flat descriptor counts. Existing model/owned
tests validate descriptor schemas/ranges. This is not an independent cell-by-cell nested-table oracle.

Deletion checks select distinct normalized main-body delText fragments >=24code points under
deleted/move-from markup, excluding textboxes and fragments also found in accepted body owners.
Five qualify in delins.docx; their hashes/counts are recorded and none occur in body output.
Short/repeated/ambiguous fragments, formatting revisions, paragraph merges, moves and complete
accepted/rejected-view equivalence remain unverified. A minimum count prevents a vacuous pass.

Original author/application provenance is not independently verified. The LibreOffice release tag
libreoffice-25.8.1.1 was resolved to immutable commit54047653041915e595ad4e45cccea684809c77b5;
its catalog response returned1000entries, so only these selected files were reviewed. Repository
COPYING.MPL/README hashes and links are recorded; Apache POI notices refer to the existing pinned
repository legal/LICENSE and legal/NOTICE. No repository-wide review/licensing claim is made.

Excluded from positive coverage: POI deep-table-cell.docx contains5000nested table levels and is an
intentional stress case beyond the bounded reader's depth policy; checkboxes.docx contains a textbox
mention but no stored main-body textbox. Neither file is part of this manifest/review. Public-document
search did not identify a suitable additional published source in this bounded pass; representative
real-world and alternative-authoring-application coverage remains open.

## Repeat offline

Fetch each exact manifest download_url to a local cache under its manifest filename, checking SHA-256
and byte size. The Java runner never fetches sources and rejects changed hashes/feature counts.
From the repository root with Java21 or later, use fresh report paths:

```sh
mvn -B -ntp -pl docweave-validation -am test \
  -Ddocweave.corpus.manifest=benchmarks/corpus/docx-complex/manifest.json \
  -Ddocweave.corpus.cache=/tmp/docweave-complex-corpus \
  -Ddocweave.corpus.report=/tmp/fresh-complex-review.json \
  -Ddocweave.inventory.manifest=benchmarks/corpus/docx-complex/manifest.json \
  -Ddocweave.inventory.cache=/tmp/docweave-complex-corpus \
  -Ddocweave.inventory.report=/tmp/fresh-complex-inventory.json
```

Default Maven/private CI needs no downloads, cache, Python or Windows VM for these checks; owned
positive/negative tests validate the helpers. The retained external reports describe the local run,
not an external-binary replay in every hosted build. Independent human rendering review is pending.
