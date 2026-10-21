# Observed-rule precedence with disjoint model tables

The explicit PdfTableLayoutProvider.preferRuled facade combines the existing closed raster-rule
detector with a caller-owned secondary provider. Both raw layout sets are completely validated
before overlap resolution. Ruled tables replace positively overlapping model proposals; disjoint
model tables remain. A shared hidden PdfTableLayouts validator now separates geometry validation
from word ownership, preserving the ordinary provider path's checks and diagnostics.

| Fixed20 measurement | Previous model/CELL profile | Rule-preferred profile |
| --- | ---: | ---: |
| Emitted tables |20 |20 |
| Complete span topologies |14 |15 |
| Matching positional/span keys |1280 |1295 |
| Exact positional/span/text cells |369 |410 |
| Strict matched/observed text tokens |792 /1769 |813 /1786 |
| Entirely exact structure/text tables |0 |0 |

Only PMC4003957_018_00.png changes its per-image measurement record:21x4/69 anchors,
all69 positional/span keys,52 exact cell strings versus11, and69/69 labeled text centers
inside their corresponding cells. Other19 measurement records remain identical. The previous
shared-weight upstream comparison had63 matching keys on this source; that pinned result is
retained in ../pdf-upstream-all20/. The current policy is uniform across all20 inputs and never
consults filenames, annotations, reference text or model class scores when preferring rules.

The corpus consists of original upstream-train scientific table crops embedded whole at150DPI
and rendered300DPI, with unchanged source/model/sample settings. Training overlap is unknown.
PAGE/CELL SINGLE_BLOCK, original English weights, RETAIN cell borders, context0, scale1,
KEEP_HIGHER_SCORE model merges, no detector and no orientation match the prior profile.
This is a scoped structural/OCR improvement; broad physical-scan accuracy, held-out quality,
calibrated confidence and PDF acceptance criteria remain separate open gates.

## Contracts and controls

The facade is a non-owning interface strategy with a hidden implementation. Construction does
not execute a provider, discover a model or start native work. Each call has its own detector,
validation and selection state. It reads the same borrowed raster without mutation, flush,
retention or an extra full-page copy. Secondary lifecycle/deadlines remain the caller/provider's.

Observed grid centerlines define the parent envelope. Each ruled anchor uses the same five-pixel
ceil/floor interior inset as direct ruled-cell OCR, excluding supported painted borders. Metadata
provenance explicitly identifies these interiors. They are not full painted cell rectangles.
Unowned PAGE words remain eligible for ordinary output; CELL extraction uses existing budgets.

Positive interior overlap suppresses an entire secondary table, with no clipped/invented cells;
border-touching tables remain disjoint. A closed drawn grid is a geometric heuristic, and errors
in that heuristic can override a model. Both raw sets must pass complete geometry validation,
including secondary tables that will be discarded. Errors and cancellation propagate. Raw
anchors from both stages consume the combined maxTableCells allowance; prospective ruled slots
are also capped before topology allocation. Existing pixel/run ceilings and a shared two-million
composition work ceiling remain, alongside integration's independent existing work checks.

Eight owned tests cover source ownership/flush/pixels, construction without execution, overlap
replacement plus disjoint retention, exact interior geometry, immutable output, empty-rule order
preservation, shared-border retention, invalid/null/overlapping/out-of-frame secondary outputs,
discarded raw-anchor budgets, pixel/dimension failure before secondary execution, preserved error
causes/interruption,20 virtual-thread calls with independent state, and a real PDF embedding with
four exact114x114 padded CELL crops, retained outside word, table geometry metadata and source image.

## Reproduction

Use the source/model/engine setup in ../pdf-merge-conflicts/ and ../pdf-pubtabnet/. Add these
test-only settings to the previous fixed-profile commands, with fresh output paths:

```text
-Ddocweave.pdf.pubtabnet.preferRuled=true
-Ddocweave.pdf.pubtabnet.geometry.preferRuled=true
```

Production callers explicitly compose the strategy and retain the managed model handle:

```java
try (final var model = OnnxTableLayout.create(modelOptions)) {
    final PdfTableLayoutProvider layout = PdfTableLayoutProvider.preferRuled(
            model, TableLimits.defaults(), ocrOptions);
    // Supply layout to the existing provider-layout or provider-layout-cell partitioner factory.
}
```

The public API acquires no ONNX/Tesseract dependency in the PDF module. Models, engines, original
images, derived PDFs and transcriptions stay local; committed evidence contains counts/hashes.
All existing default strategies remain unchanged. The ordinary provider validator refactor is
covered by its prior malformed-layout/ownership tests and the default20-image replay.

Full actual Java21/25 verification passed899 tests each, zero failures/errors/skips and strict
Javadocs, including ten installed Tesseract test families. Java21 used the composed profile;
Java25 full verification replayed default behavior. A separate Java25 composed-profile run
reproduced every Java21 cell/geometry record. Default all20 measurement records exactly match
the historical336-cell baseline, with no prefer_ruled field in its OCR report. All seven Java
source hashes stayed unchanged throughout. Pinned formatter/12 ordinary tooling guards/11
optional Python reference controls pass.
