# Isolated CELL recognition model comparison

The explicit separate-cell factory lets this fixed evaluation hold PAGE words, model layout,
source raster, segmentation and crop policies constant while changing only CELL English
weights. All20 original PubTabNet train examples are retained with the same300DPI render,
SINGLE_BLOCK PAGE/CELL modes, RETAIN borders, original PAGE model and structure-only ONNX
graph. No detector/orientation, source repair, oracle crop or per-image configuration is used.
The alternative model is the official tessdata_best English file; source revision, byte count,
SHA256 and verified Git blob identity are pinned in manifest.json. Models/sources remain fetch-only.

| CELL weights | Tables | Complete positional-span topologies | Exact positional/span/text cells | Strict matched tokens /2070 | Observed tokens | Entirely exact tables |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Original English |19 |14 |336 |791 |1765 |0 |
| tessdata_best English |19 |14 |322 |769 |1759 |0 |

Exact-cell counts improve on3inputs, regress on7 and remain unchanged on10. All gains/losses
are retained in comparison.json. Layout dimensions/anchors/positional matches and published
text-center containment observations are unchanged per emitted table. This is a negative
aggregate result, not a new default or evidence of general recognition improvement. The fixed
upstream train samples are not established as held out from either model's training.

Set `docweave.pdf.pubtabnet.cellTessdata` to a local directory containing the alternative
eng.traineddata, alongside the provider-cell baseline properties and fresh report path.
It requires PROVIDER_LAYOUT_CELLS and records optional cell_model_sha256/cell_engine_strategy
fields. PAGE weights still come from docweave.tesseract.tessdata. Absent cellTessdata preserves
the existing shared-provider factory and omits the optional fields. The current standard
cell_borders field predates this selector and was not in the older original baseline snapshot.

Focused alternative Java25 and full alternative Java21 evaluations have identical result entries.
Full Java21 and25 verify each pass874tests with strict Javadocs and ten installed-engine
families; Java25 additionally replays the original profile and exactly reproduces all20 old result
entries, with optional cell-model fields absent. This proves preservation of the original profile,
not general PDF extraction fidelity. Production is unchanged from e5bcbba.
