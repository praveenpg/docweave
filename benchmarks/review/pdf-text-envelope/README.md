# Published text-envelope coverage

This fetch-only geometry diagnostic uses all20 original PubTabNet train images and pinned
published cell rectangles. The same structure graph sees the same whole-image PDF embedding
at150DPI/render300DPI as the OCR profile. It runs no OCR engine. All1230 valid published
text boxes stay in the denominator, including absent matching positional-span anchors;
150 other anchors have no geometric text label. Original assets/labels remain fetch-only.

Source boxes are transformed using the actual PDF drawing scale (float media-box extent,
source dimensions and rendering DPI), not the ratio of rounded raster dimensions. Mathematical
cell rectangles and actual source-crop rectangles are measured separately: cell OCR rounds
left/top down and right/bottom up. Its white padding does not recover additional source pixels.
A separate one-original-pixel tolerance diagnoses annotation/render rounding; it does not
expand a source crop or modify the raw intersection-area metric.

Paired Java21/25 records are identical. Of1230 valid labels,131 have no matching anchor,
1045 centers fit,311 full rectangles fit mathematical cells,675 fit with one-source-pixel
tolerance, and454 fit actual rounded source crops. Mean per-label rectangle-area overlap
with rounded crops is0.79338, including zeros for missing anchors. This is an unweighted
mean of label fractions, not total text-pixel coverage or recognition accuracy. Annotation
rectangles are not glyph outlines; missing positional-span correspondence does not prove
printed text is absent. These results identify geometry risk, not a safe crop-repair rule.

Early local trials used normalized raster/source dimensions and omitted crop rounding;
those counts are superseded by the source-frozen paired reports here. Analytical controls
cover full/partial/zero overlap, separate tolerance, rounded recovery, missing-anchor zeros
and malformed annotation classification. Full Java21/25 verify passes876tests each with
strict Javadocs. No installed OCR flags were selected in these runs; prior874-test native
engine verification remains separate. Production extraction is unchanged from e5bcbba.

Reproduce with fresh output and local original assets/model:

```sh
mvn -B -ntp -Dmaven.repo.local=/tmp/docweave-m2 verify   -Ddocweave.pdf.pubtabnet.geometry.cache=/path/to/originals   -Ddocweave.pdf.pubtabnet.geometry.report=/tmp/fresh-coverage.json   -Ddocweave.pdf.pubtabnet.geometry.dpi=300   -Ddocweave.pdf.pubtabnet.geometry.layoutModel=/path/to/structure.onnx   -Ddocweave.pdf.pubtabnet.geometry.layoutSha256=ff1d19a18cb5f041c1692ff681f891a5180f9430d68a71d2cf7a0a2e8e55b990
```

No detector/orientation or source-specific parameter tuning is enabled. The examples are not
established as held out from model training; no calibrated confidence or general PDF parity follows.
