# Two retained scan discrepancies

These development-selected cases explain existing misses; they are not a held-out quality
benchmark or an improvement claim. All source pins, annotations and historical reports
remain unchanged. No extraction policy or default changes in this checkpoint.

`CaptureDetectedTableInput.java` embeds the original whole JPEG at150DPI, renders150DPI,
and runs the unchanged detector, crop planner and structure topology machinery. It reads
no annotation, invokes no OCR and changes no object/score. It selects the already-tested
KEEP_HIGHER_SCORE merge policy. Existing end-to-end OSD abstains on both inputs, so no
orientation correction is omitted relative to those runs. This standalone launcher is a
diagnostic, not a substitute for aggregate pipeline resource-limit tests.

On actual Java21 and25, every detector/crop/structure/anchor TSV is byte-identical, and
emitted table dimensions/anchor counts reproduce the existing end-to-end reports.
The helper compiles with release21 and passes strict Javadoc checks under both JDKs.

## Small modern table: cTDaR_s103

The detector produces one crop, x58..538/y798..894. Structure inference contains four row
objects, three column objects, and one spanning-cell proposal over columns1..2 of row0.
The resulting4x3 table has11 anchors. Eight positional/span keys match the nine published
keys. The source annotation spans row0 across all three columns; the model instead
emits a blank first-column anchor plus the two-column header. A blank row1/column0 anchor
is also emitted but absent from the published annotations.

This exposes a header-span disagreement and incomplete empty-cell labeling. It does not
justify expanding all header spans across blank stub columns. Table text and empty-cell
semantics are separate from strict anchor-key equality. The detector's content envelope
also differs from the annotation's wider ruled table envelope. No rule or annotation
change is promoted from this one example.

## Historical photographed page: cTDaR_s003

The detector emits one ordinary-table object with raw score0.513383 (not a calibrated
confidence). Its x309..1315/y620..2730 crop intersects both published table envelopes,
clips the upper part of the first, and includes regions outside either annotation.
No second detector object survives the existing model decoder threshold. Structure
inference on that crop yields4x2/six anchors, matching neither annotated topology.

This identifies an inadequate region proposal before final cell assembly. Raising the
score threshold would drop the only candidate; lowering it cannot recover a second
candidate from the already-thresholded object list. Neither action establishes correct
page segmentation. Source skew, worn/open rules and different historical typography
remain visible domain limitations; no claim about their individual causal effect follows.
A model/preprocessing comparison is more defensible than a case-specific merge repair.

## Evidence and reproduction

Paired directories contain only source hashes, runtime identities and numeric TSVs.
Detector boxes and crop envelopes are in rendered page pixels. Structure objects and
anchor boxes are relative to the corresponding crop; add the recorded integer crop
origin once to compare page geometry. Published envelopes in summary.json are original
source pixels; whole-page rendering targets150DPI but do not assume arbitrary future
sources have an exact unit transform after PDF dimension rounding.

`manifest.json` records graph/source-manifest references, the evaluated production
commit, the helper SHA256, capture hashes and exact local compile/doclint/run scripts.
Use the existing explicitly installed graph and classpath caches; there are no automatic
downloads. Full JPEG/XML sources, rendered derivatives, weights and transcriptions are
not redistributed. The modern and historical cases are separate domains, not a single
scan-accuracy score. PDF completion remains an open acceptance decision.
