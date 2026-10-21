# Original figure/caption placement associations

This adds independent physical-parent evidence alongside complete-body caption recall.
All five previously observed scientific originals are retained: **94pages,31 raster
occurrences,21 main-body figures and10 nonfigure occurrences**. No production change,
new extraction option, reference repair or claimed original-source accuracy gain occurs.

| Source | Pages | Numbered figures | All raster occurrences | Native lines / source-tagged lines | Native compound columns |
| --- | ---: | ---: | ---: | ---: | ---: |
| PLOS0324609 |15|5|7|5/5|5/5|
| PLOS0330904 |14|1|3|1/1|1/1|
| eLife90049 |29|4|9|4/4|4/4|
| PeerJ21002 |27|6|6|6/6|6/6|
| Scientific Reports36762 |9|5|6|4/5|5/5|
| Total per uniform profile |94|21|31|20/21|21/21|

Every emitted caption relation is scored. All15 source/profile extractions succeed,
with zero false associations and all31 raster placements matched per profile. The
one Scientific Reports miss in each line profile stays in its denominator. These are
same-ordinal/page/raster association counts, not complete-caption text counts. A
partial caption can identify the right source raster while failing the separate
complete-body scorer; its column result remains19/21, including two PeerJ physical-
hyphen/XML differences. No arbitrary hyphen removal is credited.

## Independent reference method

Poppler26.10.0 `pdfimages -list` and `pdftohtml -xml -zoom 1` supply each complete
original's raster inventory. Every image-bearing source page was independently
rendered and visually reviewed to assign figure ordinals or nonfigure roles before
that source's association evaluation. Other pages contain no raster occurrences.
PLOS0324609 was evaluated first; the other four references were then frozen before
their expanded runs. The originals were already observed in caption development,
so this is development evidence, not blinded/held-out source selection.

The10 negative occurrences are repeated Crossmark/open-access icons in two PLOS
papers, five repeated ORCID icons in eLife and one publication-date raster in
Scientific Reports. They represent four visual roles, not ten independent layouts.
PeerJ has no nonfigure raster occurrences. Larger decorative/caption-like negatives
remain underrepresented.

Integer-rounded rectangles use top-left PDF points and a predeclared two-point
per-edge tolerance, accounting for independently rounded origin and extent. No
nearest-reference choice, fuzzy caption matching or selected crop enters extraction.
A prediction must have the same caption ordinal, physical page and independently
labelled raster bounds. Every duplicate, unknown ordinal, wrong page/image or
nonfigure association is a false positive. Missing references remain. Inventory
separately retains every occurrence and unmatched raster.

Exact source sizes/SHA256, source distributions, references, production caption
policy and scorer identities are pinned in the numeric manifests. The expanded
scorer only generalizes its complete-population guard to explicit per-source counts;
matching formulas, profiles and tolerance are unchanged. The first7/5 reference
remains compatible, and its entire original `.results` tree reproduces exactly under
a JVM assertion. Both initial and expanded pre-run identity manifests are retained.

## Validation and limits

The JVM scorer checks swapped images, duplicate captions, wrong pages, unknown
ordinals, nonfigure links, absent bounds and duplicate image inventory before each
real extraction. All controls, compilation, private strict Javadocs and the pinned
formatter pass on Java21. No new full Maven run was needed for benchmark-only work;
the last production build remains1,191 passing Java21 tests. No Java25/publication.

Compile `benchmarks/tools/ScoreFigureAssociations.java` against the Java21 reactor
runtime/Jackson classpath, then run `dev.docweave.partition.ScoreFigureAssociations`
with the selected `*-reference.json`, exact original PDF and fresh report path.
Use root `reference.json` for PLOS0324609 and `expanded/` for the other four sources.
Run all three fixed profiles for each complete original, without selecting winners.

The PDFs, source-rendered images and Poppler XML remain outside Git. Only numeric
references, identities and results are retained; existing extraction warnings remain.
Placement associations do not establish complete physical figure boundaries, vector
figure reconstruction, pixel fidelity, calibrated semantic confidence or broad
precision on unseen layouts. PDF quality/acceptance gates remain open.
