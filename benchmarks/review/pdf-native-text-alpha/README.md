# Explicit zero-alpha native text filtering

`PdfOptions.TextRendering.NONZERO_ALPHA_PAINTED_MODES` adds explicit graphics-state
selection before PDFBox duplicate suppression and native ownership/OCR selection.
It retains a glyph when at least one enabled fill/stroke operation has positive
alpha. A positive fractional value has no minimum visibility threshold. Modes 3
and 7 remain excluded. Every visited source position is charged before filtering.

Existing `ALL` and `PAINTED_MODES` remain unchanged. The new native text/table
provenance is `nonzero_alpha_painted_modes_v1`; OCR and images do not inherit it.
No provider is discovered or invoked automatically. Filtering a searchable layer
can make a page eligible for an explicitly supplied textless-page OCR provider.

This is not rendered visibility. Clipping, soft masks, transparency-group
compositing, white-on-white paint, occlusion and optional-content visibility remain
outside the policy. It does not apply redactions or change encoded source bytes.

Four owned JVM tests cover all eight rendering modes crossed with four independent
fill/stroke opacity combinations, positive fractional/tiny alpha, coordinate
preservation, transparent-before-opaque overlapping copies, restored graphics
states, source glyph budgets, OCR selection/provenance and retained clipped text.
The independently authored truth table retains 14 of 32 mode/opacity runs;
historical `PAINTED_MODES` retains 24 and `ALL` retains 32. Existing six rendering
tests remain unchanged and pass. No Python tests or new dependencies are added.

## Original-document regression

The same three complete PLOS/eLife scientific originals and complete 11-page
NVIDIA release are run under all three policies and both native/source-tagged
factories. All **24/24** case/factory/policy observations succeed. Within each
factory, every emitted element equals its `ALL` control after removing only the
declared rendering-policy metadata. Text, IDs, geometry, table cells/topology and
all other metadata remain exact. Neither empty-table cases nor missing source
tables are omitted. No model or OCR is invoked in this regression.

This establishes unchanged extraction on these originals, not an original-source
accuracy gain or general visibility correctness. The owned transparent-layer
fixtures establish the new behavior; there is no independent visibility oracle
for these four original documents. Existing NVIDIA strict row scores are unchanged.

`manifest.json` pins the source lengths/SHA256, evaluated code and fixed Cartesian
product before extraction. A subsequent documentation-only change adds missing
private glyph-component Javadocs found by strict doclint; evaluated and final
source hashes are both retained. The report contains numeric outcomes, not source
transcriptions. Source/model/PDF bytes are not redistributed.

Full offline Java 21 `mvn verify` passes **1,155** tests with zero failures, errors
or skips and strict public Javadocs. Strict private Javadocs pass on all three
changed production files, the new test and standalone tool. Pinned formatting and
`git diff --check` pass. Java 25 remains deferred.

For reproduction, prepare a four-column TSV with safe case ID, original local PDF
path, byte length and SHA256 for every manifest input. Compile
`benchmarks/tools/CompareNativeTextAlpha.java` against the validation module Java 21
test classpath, then run it from the repository root with headless AWT, the TSV
and a fresh report path. No input is cropped, repaired or chosen by output quality.
Relocation changes the TSV hash; per-source byte identities remain authoritative.

The opt-in official GitHub freshness check also passes on Java 21 on 2026-10-20:
latest release and reviewed main remain `0.27.30` /
`08d407ea5ab77dcc7f27775c22cc70bbedae7d58`. The original saved reference
commit is unchanged. `upstream-java21.json` retains response identities. The
initial sandbox network failure was rerun with network access; only the successful
online observation supports this freshness statement.
