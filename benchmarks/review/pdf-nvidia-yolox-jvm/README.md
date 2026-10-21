# JVM-only alternative detector proposal experiment

The original NVIDIA release and six statement page regions remain unchanged. The frozen
manifest binds the complete original input, prior reference manifest, detector graph, source
decoder identity and finalized JVM probe hash before first inference. Every original page is
rendered at150DPI; references enter only the downstream geometry comparator.

The explicit local nonquantized YOLOX graph has the same pinned hash used in the earlier
reference detector experiment. Its fixed contract is FLOAT images[1,3,1024,768] and
output[1,16128,16]. The JVM probe uses unnormalized RGB planes, top-left114 padding,
pixel-center bilinear interpolation rounded to uint8, strides8/16/32, strongest class times
objectness>.25 and class-agnostic inclusive-area NMS.1 before selecting table class8.
Stable query-index ties and post-NMS clipping are explicit. This implements a JVM diagnostic;
its interpolation is not asserted bitwise equivalent to upstream OpenCV preprocessing.

On Java21, all11 original pages complete. On the four pages containing the six fixed regions:

| Fixed detector configuration | Table proposals | One-to-one matched regions at IoU>=.5 |
| --- | ---: | ---: |
| Earlier thresholded TATR proposals |4|3/6|
| JVM YOLOX after class-agnostic NMS |6|6/6|

The alternative separates the two statement regions on pages5/6 and includes label-column
areas omitted by the earlier detector. This is a proposal-geometry gain on one development
source, not recovered table topology, row-text accuracy, calibrated confidence or a comparison
of complete libraries. The110 strict statement-row references are unchanged and have not
yet been scored at the time of this detector-only experiment. The subsequent
[structure/native-text replay](../pdf-nvidia-proposal-structures/README.md) retains
0/110 strict statement rows despite the6/6 region associations; the
[cutoff study](../pdf-nvidia-structure-cutoffs/README.md) also finds no strict gain.
Existing native
and TATR model results remain preserved. Unmatched proposals are not called false positives:
the selected region labels do not annotate all tables in the complete document.

`CompareDetectorProposals.java` verifies source/frame/pin identities and uses maximum-cardinality
one-to-one matching; a large candidate cannot receive duplicate reference credit. Owned JVM
controls cover that rule, augmenting reassignment, empty pools and IoU arithmetic. Seven owned
probe controls cover RGB channels, edge/padding/interpolation, grid/class decoding, competing
non-table suppression, disjoint proposals and the strict score threshold. Both launchers expose
`--self-test`; compilation and strict private Javadocs pass. No Python test or dependency was added.

The graph is fetched explicitly for this experiment and is not distributed or downloaded by
the library. The probe requires PDF/hash, model/hash and a fresh report, under an external120second
process limit. Use headless mode and explicit ONNX native access. This is not a hardened public
provider or production lifecycle/deadline contract. No production defaults/dependencies,
release/tag/Central operation or Java25 run. Java25 final PDF verification remains deferred.

Next evaluate these unchanged proposals through bounded structure recognition and original
native glyph ownership, then retain earlier corpus regressions before considering production
integration. No reference-guided crops, threshold tuning, repaired labels or best-of scoring.
