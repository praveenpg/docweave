# Raster-rule stage audit and compact-table prototype

Four fixed raster-rule profiles are evaluated on the same9 original cTDaR whole-page inputs,
rendered150DPI without source repair or per-case settings. Modern business/scientific PDFs
remain the first development priority. This development-selected small set supplies structural
labels, not text transcription, held-out accuracy or general business-document coverage.

| Fixed profile | Minimum run / column / row pixels | Modern exact topologies (12 refs) | Historical extra TABLE_LIMIT inputs |
| --- | --- | ---: | ---: |
| Historical baseline | 64 / 32 / 24 | 0 | 0 |
| Chromatic lines | 64 / 32 / 24 | 0 | 2 |
| Chromatic and compact | 32 / 16 / 12 | 2 | 3 |
| Compact, original dark threshold | 32 / 16 / 12 | 2 | 0 |

Both compact profiles recognize the original s10217x4/68-anchor and5x4/20-anchor tables with
exact dimensions and complete positional/span sets. Envelope IoUs are approximately0.995
and0.982. These same tables were already model successes and300DPI rule successes, but the
prototype recognizes them at150DPI without executing the optional model or OCR. This is a
scoped rule-only improvement, not additional successes over the whole model/composed pipeline.

All9 inputs/20 references remain for every profile. Oversized s001 stays OCR_LIMIT. The dark
compact profile admits the same other eight inputs as baseline, emitting only those two tables.
No new historical table or extra limit is observed in this fixed set. That is not evidence of
general false-positive safety. Chromatic profiles produce additional TABLE_LIMIT outcomes and
no topology benefit beyond dark compact. Resource ceilings are unchanged; limits are not hidden
or raised. Source labels, missing blanks and predictions are never repaired.

## Stage findings and negative controls

Baseline s102 already has26 horizontal/10 vertical segments and two closed candidate grids;
both are rejected by minimum cell size. Dark compact preserves those segment counts and accepts
both. Baseline s101 has9 horizontal/no vertical segments; s103 has3/1. Baseline s104 has none.
Adding chromatic pixels yields s10415/11 segments, or29/12 with shorter runs, but neither has
an accepted complete topology candidate. Thus color extraction alone does not fix that page.
Zero topology candidates does not identify every underlying closure/connectivity/overlap cause.

The audit records raw segments, complete topology candidates before size/remnant filters,
size/remnant rejections and accepted grids. Stage reconstruction must exactly reproduce the
compiled detector entry point. Rejected partial/nonrectangular/overlapping/open geometry is
not invented into tables. Normalized IoU comparison maps through encoded PDF physical extent,
not the floored canvas ratio. Reference association is maximum-cardinality one-to-one geometry
at IoU>=0.5; dimensions/spans are scored only afterwards, without a topology-driven chooser.

Seven independently authored controls run for each profile on both JDKs: a normal closed grid,
compact closed grid, colored compact grid, gray compact grid, cells below minimum size, a broken
outer border and a thick filled block. Accepted grids must match authored dimensions and all
unmerged anchors. Dark compact accepts normal/compact black grids and rejects the other five.
Chromatic compact additionally accepts the authored colored grid. These drawing controls are
not real-photo classification, OCR text, merge-ambiguity or held-out quality evidence.

An earlier candidate changed only minimum cell dimensions while retaining runs>=64. Both JDKs
failed the broken-outer-border control before corpus evaluation. Short outer remnants disappeared
from extraction, permitting a smaller closed region. It was rejected, not silently excluded from
the experiment history. See rejected-long-run-compact.json. The final compact candidate also
retains runs>=32 and passes this specific control; that does not solve arbitrary damaged borders.

## Isolation and reproducibility

Production source/defaults/public APIs are unchanged. The runner uses the exact archived baseline
source from fc42a66, SHA25604475b2ef5b7803a89b7db097e31e6e3de200e623b7bb1a5155b1c3835b83825.
It exposes two private helpers only in temporary source copies for stage inspection, applies
explicit fixed substitutions and compiles each variant into its own directory. A dedicated JVM
places that variant first in the diagnostic classpath. Do not use these prototype classes as a
production adapter. Baseline changes access only; production artifacts are never overwritten.
Shared source hashes, prototype hashes, runtime identities and all numeric captures are retained.

The chromatic predicate accepts the original maxRGB<=80 pixels plus colors with minRGB<=160,
maxRGB<=224 and RGB spread>=32. It is a fixed diagnostic, not adaptive binarization or calibrated
line classification. Compact retains original dark pixels. The8-pixel thickness,4096-run/segment,
64M directional-pixel,16M source-pixel,256 prospective-slot and2M geometry-work ceilings remain.
Audit and actual-entry-point replay have separate work counters; this is not a combined production
transaction. Each source raster is borrowed unchanged and released by the helper after inspection.

```sh
python3 benchmarks/tools/audit_raster_rules.py \
  --jdk /path/to/jdk21 --classpath /path/to/runtime-classpath.txt \
  --captures /path/to/pinned-region-capture21 --pins /path/to/jpeg-pins.tsv \
  --output /path/to/NEW-audit21
# Repeat with actual JDK25 and its identical pinned region capture, into NEW-audit25.
python3 benchmarks/tools/compare_raster_rule_audit.py \
  --java21 /path/to/audit21 --java25 /path/to/audit25 \
  --cache /path/to/original-ctdar \
  --manifest benchmarks/review/pdf-physical-scans/extended-manifest.json \
  --output /path/to/NEW-summary.json
python3 -m unittest discover -s benchmarks/tools -p 'test_*.py'
```

Build matching core/PDF classes and assemble an explicit runtime classpath before running. The
runner requires local resources and downloads nothing. Reproduce input rasters using the earlier
region-capture procedure. Original JPEG/XML, derivatives, model weights and transcriptions remain
fetch-only. The archived source is project-owned reproducibility evidence, not an application API.
Optional Python tooling is not a JVM runtime dependency.

All231 numeric/control files per actual JDK match (462 total). The helper compiles with release21
and passes strict standalone Javadocs on21/25 for all four isolated profiles. All26 offline tooling
guards pass; two new guards reject changed/unknown prototype sources and preserve baseline/access,
dark-pixel and resource-cap boundaries. Production Java is unchanged; the prior905-test baseline
is not claimed as a new local full Maven run for this benchmark-only change.

**Decision:** reject chromatic promotion; advance the dark compact profile to implementation
review as an explicit opt-in strategy, preserving historical defaults. Next implement it through
interface factories/immutable values with hidden implementations, shared existing limits and
owned ownership/geometry/boundary tests. Re-run the source-pinned independent cases through the
actual production facade before a beta milestone. No new beta is justified by prototypes alone.
Broader colored/open/complex-native/OCR/held-out/calibration gates remain open.


The first hosted run caught a standalone compilation issue: direct calls to private stage helpers
compiled only against temporary access-exposed copies. The diagnostic now invokes those fixed
private helpers through reflection, preserves typed I/O causes, and compiles against unmodified
production classes without public access bridges. StandaloneSourceCompatibilityTest passes locally
on actual21/25; all four profiles were rerun on both, reproducing every231-file numeric/control
capture exactly. The failed hosted run is retained as history; only the corrected run may establish
full CI success. This changes no production code or prototype recognition policy.
