# Frozen-code new-publisher caption transfer

The first official PeerJ result for the recorded discovery query was selected
before any DocWeave extraction, with product code frozen at `4616a4d`. The same
article was retained when the publisher landing page, PDF and XML returned403;
those retrieval failures are not counted as extraction failures.

NIH NLM NCBI PubMed Central (PMC) Article Datasets supplied an authorized CC BY
published version of DOI10.7717/peerj.21002, PMCIDPMC13086023, version1. It is marked
as a published article, not an author manuscript, in the distribution metadata.
The PDF has27pages and River Valley/pdfTeX production metadata. Original XML
identifies the same DOI and CC BY4.0 license. Both files match the distribution
MD5 checksums; exact lengths and SHA256 values are frozen before extraction.
Byte equality with the inaccessible publisher endpoints is not asserted.

The data source is **NIH NLM NCBI PubMed Central (PMC) Article Datasets – Full-Text
Biomedical and Life Sciences Journal Articles on AWS**, accessed2026-10-20.
The [current official cloud documentation](https://pmc.ncbi.nlm.nih.gov/tools/pmcaws/)
and [dataset README](https://pmc-oa-opendata.s3.amazonaws.com/README.txt) describe
public anonymous retrieval. The old OA endpoint returned404 because that service
[was retired in August2026](https://pmc.ncbi.nlm.nih.gov/tools/oa-service/).
No access-control workaround, login or AWS credential was used.

## Complete source population and result

The source-only inventory retains **six main-body figures**, each with one label,
caption and graphic reference. All six complete caption bodies stay in every
denominator; no source/reference was selected based on candidate output.

Each configuration runs all three uniform profiles: native lines, native column
paragraphs and source-tagged tables with ordinary lines. The predeclared factorial
comparison uses caption NONE/HORIZONTAL and Unicode RAW/PRESENTATION_FORMS.
All **12/12 complete-document runs succeed**:

| Caption policy | Native Unicode policy | Exact complete captions uniquely present as text, each profile | Classified complete captions, each profile |
| --- | --- | ---: | ---: |
| NONE | RAW |4/6|0/6|
| HORIZONTAL | RAW |4/6|0/6|
| NONE | PRESENTATION_FORMS |4/6|0/6|
| HORIZONTAL | PRESENTATION_FORMS |4/6|0/6|

Within each Unicode policy, all-text hash/presence/order results and reference
identities remain exact when caption detection is enabled. Presentation expansion
changes five overall text codepoints, but provides no complete-caption match gain.
Ordinals2and3 remain exact-text nonmatches under every configuration. This does
not establish that their characters were lost: the strict contiguous reference
criterion fails, and their detailed attribution remains open. No fuzzy alignment,
Unicode/body repair, reference change or excluded failure receives score credit.

A read-only whole-original eligibility audit finds all six labelled units beside
one eligible raster each, but the mandatory-period label predicate fails: PeerJ's
numbered figure labels have no following label period. The geometry thresholds
were not widened. This is an explicit cue-subset limit, not a regression in the
previous PLOS/eLife development results. Broad caption recognition and two exact
body nonmatches remain open; this transfer does not establish general quality or
independently human-reviewed acceptance.

## Reproduction and validation

The source freeze is commit97bfe25, before candidate evaluation. Selection,
retrieval observations, source identities, source-only inventory, qualification
and the complete four-job protocol accompany the results. All extraction/scoring
is JVM-based. Scratch configuration patches change only the source-population
guard and explicit caption/Unicode policies; baseline reference/scoring code stays
unchanged. The eligible-cue audit uses the actual committed horizontal cue and
adjacency predicates. Every original/profile result remains in its full report.

Fetch the exact published-version objects listed in `source-manifest.json`, verify
their distribution MD5 and frozen SHA256/length, and keep the bytes in an explicit
local cache. Copy the source inventory, scorer and eligibility helpers to scratch
sources and apply the corresponding recorded patches. Compile with Java21 and the
validation module test classpath. Run the source inventory first, then every
predeclared job with explicit `docweave.caption.transfer.horizontal` and
`docweave.caption.transfer.presentation` booleans, headless AWT and fresh reports.
Do not treat unavailable or changed source bytes as a passing test.

The copied inventory/scorer compile and pass strict private Javadocs; all12 runs
and within-normalization invariance checks pass. No product code changed in this
transfer, so the previous complete Java21 verification remains1,159 passing tests.
No Python test/dependency or Java25 run is introduced. Original PDF/XML, publisher
transcriptions and rasters remain outside Git.

Next work should investigate explicit model-backed caption regions without
weakening the punctuation-based policy's existing prose-reference negatives.
Return to the two exact-caption nonmatches as a separate text-fidelity task;
do not hide them through normalization or per-case strategy selection.

### Subsequent source attribution

Independent full-page Poppler renders and positioned text on original pages12/13
show printed word wraps `pro-` / `gressive` and `clini-` / `cal`. Native column
output retains those hyphens and inserts its ordinary interline space. The XML
has uninterrupted words. These two strict nonmatches therefore reflect retained
printed wraps, not omitted caption prose. Exact source/result metrics remain4/6;
no output or reference was repaired. Render identities and this read-only
observation are recorded separately; rasters/transcriptions remain outside Git.
General discretionary-hyphen reconstruction remains a separate explicit-policy
question because indiscriminate deletion can change genuine hyphenated compounds.
