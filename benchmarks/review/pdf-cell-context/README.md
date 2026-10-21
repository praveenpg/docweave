# Explicit bounded provider-cell source context

PdfOcrOptions.withCellContextPixels accepts0through16 rendered input pixels for
PROVIDER_LAYOUT_CELLS only. Existing constructors/defaults retain0. Positive context
extends each source anchor crop, clamps it to the provider table, then applies existing
outward floor/ceil rounding and12-pixel white padding. Metadata records the selected
context/policy; reported table/cell geometry remains the original provider geometry.
Other modes ignore this option. Source pixels are never modified.

The same private crop-bounds calculation drives all-crop preflight and actual copying.
Existing per-crop pixel and aggregate64M fill/copy work ceilings include expanded crops
before any CELL call/allocation. Shared raw PAGE/CELL output and table text limits remain.
External recognizers retain their own process/admission/allocation policies. Source context
may include neighboring content; this is an explicit geometry heuristic, not text repair.

Owned controls inspect exact interior/outside-table pixel markers and unchanged reported
cell rectangles, a crop that fits at context0 but fails the same ceiling at16 before any
CELL call, constructor/copy compatibility, ignored word-only policy and all four source
quarter-turn transforms. Existing default/no-context regression controls remain.

The fixed all20 PubTabNet comparison uses unchanged model/engine/language/layout/DPI,
SINGLE_BLOCK PAGE/CELL and RETAIN borders. Only uniform4-pixel context changes. It yields
19tables/14 complete positional-span topologies and320 exact cells versus336 without
context;747 matched strict tokens versus791,1753 observed versus1765,zero entirely exact
tables. Exact-cell counts improve on2images, regress on6 and remain unchanged on12.
These negative aggregate findings do not justify a default change or automatic expansion.
Each positive/negative result is retained; training overlap remains unknown.

Use the prior provider-cell corpus command with a fresh report and
`-Ddocweave.pdf.pubtabnet.cellContextPixels=4`. Absent/zero context retains the original
report fields and crop geometry. Positive context requires provider-cell mode in this
harness and is asserted against actual emitted table metadata. Models/source assets are
local and fetch-only; runtime performs no discovery/download.

Full actual Java21/25 verify passes879tests each with strict Javadocs and ten installed
engine families. Java21 runs context4/all20; its entries exactly match the focused Java25
context run. Full Java25 replays context0/all20 and exactly reproduces historical entries,
with the optional context report field absent. Per-table dimensions/anchor positions/source
text-center diagnostics stay unchanged. No source/golden/model/default is repinned.
