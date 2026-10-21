# Frozen model-cell native baseline inventory

This Java 21 diagnostic inventories all 11 original NVIDIA pages and both frozen
detector/structure profiles. It reads no reference row strings and performs no
inference, crop selection, cell splitting, topology repair or text normalization.
Every cell and empty page remains in `java21.json`; native glyph strings are not
redistributed. Model capture and original PDF/raster hashes are checked before use.

| Complete original-document inventory | TATR | YOLOX |
| --- | ---: | ---: |
| Pages retained | 11 | 11 |
| Captured cells | 572 | 711 |
| Cells with multiple native baseline clusters | 16 | 35 |
| Assigned nonblank native glyph positions | 4,412 | 6,574 |

These are diagnostic counts, not counts of correct cells or recovered rows.
The original strict statement score remains **0/110** for both profiles.
Unassigned glyphs include ordinary narrative and headings; they are not all
missing table content. Glyph counts do not measure transcription accuracy.

YOLOX page 4 row 1 contains two baselines in each of four date/header cells.
On pages 5–7, multiline cells also occur in label columns and full-width spanning
cells. A two-baseline cell therefore does not establish two logical rows; blanket
splitting risks changing wrapped labels and notes. No production/default change
is justified by this inventory. The existing explicit `PRESERVE_LINE_BREAKS`
policy already retains native line distinctions without altering table topology.

Assignment uses the same displayed-point/pixel scaling and half-open glyph-center
ownership convention as native provider extraction. Only nonblank upright glyphs
enter clusters. Sorted baselines within two points of a cluster's first member
are grouped without transitive chaining. This inventory is distinct from the
full production text/grouping pipeline; it does not infer semantic headers or
physical row correctness. Cross-cell duplicate ownership rejects the audit.
The original two-million geometry-work ceiling applies independently per page
and profile; it is not raised for the audit.

Four JVM controls cover inclusive tolerance/nontransitive grouping, empty cells,
shared-edge ownership and half-open extents. Initial execution incorrectly shared
one page-work counter across the document and stopped with `TABLE_LIMIT`, writing
no report. The corrected complete run retains every page/profile. The manifest
records both tool identities and this correction; no source/model/settings changed.
Compilation, strict private Javadocs and the pinned formatter pass. No product
source changed; the preceding complete Java 21 verification remains 1,151 tests.

To reproduce, compile `benchmarks/tools/AuditModelCellBaselines.java` on the
validation module's Java 21 test classpath. Run `--self-test`, then supply the
original PDF, frozen `pdf-nvidia-proposal-structures/replay-manifest.json`, raster
root, structure-capture root and a new report path. Run from the repository root
with headless AWT. All local assets must match the existing frozen identities;
nothing downloads automatically. Neither source/model binaries nor publisher
transcriptions accompany this evidence.
