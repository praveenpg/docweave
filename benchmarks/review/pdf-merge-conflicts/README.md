# Explicit score-ordered rectangular merge conflicts

The default OnnxTableOptions.MergeConflicts.REJECT_TABLE is unchanged. Explicit
KEEP_HIGHER_SCORE retains an already accepted higher-score merge and discards an entire
overlapping lower-score rectangle. It does not shrink or split a prediction. Equal-score
conflicts, nonrectangular slot selections, ambiguous axes and overlapping final tables still
reject a candidate. Discarded proposals cannot mutate slot ownership. All geometry work remains
bounded/interruptible, including comparisons for discarded proposals; no limits are increased.

The motivating case and actual upstream comparison are separate evidence in ../pdf-upstream-table/.
This is an independently implemented geometry policy, not a port or parity claim for upstream's
OCR-driven conflict shrinking/axis refinement. Scores rank model predictions, not extraction accuracy.

All20 original PubTabNet train images use the fixed300DPI PAGE/CELL SINGLE_BLOCK/RETAIN
profile, original English weights, the same structure graph, no detector/orientation/context/
magnification, and no per-image adjustment. Source images/text/models remain fetch-only.

| Measurement | Default | Explicit policy |
| --- | ---: | ---: |
| Emitted tables |19 |20 |
| Matching positional/span keys |1182 |1280 |
| Exact positional/span/text cells |336 |369 |
| Completely matching span topologies |14 |14 |
| Entirely exact text tables |0 |0 |
| Strict matched/observed text tokens |791 /1765 |792 /1769 |

Only PMC1626454_002_00.png has different per-image measurement records. Its9x12 table
has99 anchors versus100 published,98 matching positional/span keys,33 exact cells and
96/97 labeled centers inside corresponding cells. Other19 measurement records are identical;
selected tables' provenance records the policy. Default replay of all20 historical measurement
records remains identical and its OCR report has no optional policy field.

This fixes complete rejection of one conflicting prediction set, while the remaining structural/
OCR errors stay visible. It does not establish general or held-out-model quality. Plain OCR text
already existed in the default fallback;33 structured cell matches do not mean33 previously
missing words were recovered. The independent geometry profile reduces missing valid labeled
anchors from131 to35:25 different spans at origin, six covered origins and four outside-grid
extents, with no no-table case. Labels without text boxes remain separate.

Six owned controls exercise higher-score selection independent of encounter order, a collision
after an unowned slot (no partial ownership), equal-score rejection, nonrectangular rejection,
duplicate estimates, cell limits/interruption and former-constructor/copy/null compatibility.
The selected provider's provenance explicitly records merge_conflicts=keep_higher_score;
the default provenance is unchanged.

Reproduce with the existing source-cache/model instructions in ../pdf-provider-cell-ocr/ and
../pdf-text-envelope/, adding:

```text
-Ddocweave.pdf.pubtabnet.layoutMergeConflicts=KEEP_HIGHER_SCORE
-Ddocweave.pdf.pubtabnet.geometry.layoutMergeConflicts=KEEP_HIGHER_SCORE
```

Use fresh report paths. The first flag selects the end-to-end PAGE/layout/CELL profile;
the second selects the separate structure-only geometry diagnostic. Production callers select
the enum on their explicit local OnnxTableOptions instead of these test-only properties.

The opt-in Java upstream status routine still detects the known historical0.27.27 reference pin
versus released0.27.28/main1b02b4d189c260ed9b1aca29583203e233d853cb. Its separately saved
report records the same six already-triaged commits and OCR-agent cause-propagation change;
the assertion is an expected drift signal, not a passing freshness check or product regression.
No reference goldens are repinned. The passing ordinary build and this failed online drift
assertion are reported separately.

Full actual Java21/25 verify passed891 tests each with zero failures/errors/skips and strict
Javadocs, including ten installed Tesseract test families. Java21 ran the explicit policy;
Java25 full verification replayed default behavior. A separate Java25 explicit-policy run
reproduced every cell/geometry record from Java21. Source hashes stayed identical throughout.
Pinned formatter,12 ordinary tooling guards and six optional Python reference controls pass.
