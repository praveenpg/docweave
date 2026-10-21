# Independent source-tagged table topology baseline

Seven original one-page PDF/UA-2 corpus files were frozen before extraction. They
contain three distinct rendered layouts: a 5x4 merged table, a captioned 4x2 table
and five identical-pixel 2x5 tagging variants. Results retain all variants but do
not present them as seven diverse physical layouts or business-document accuracy.
Sources are attributed to the [veraPDF corpus contributors](https://github.com/veraPDF/veraPDF-corpus)
under CC BY 4.0. Original PDFs and derived renders remain outside Git.

Physical row/column positions, spans, header roles and source regions were fixed
from independent original-source inspection before DocWeave extraction. The labels
contain 74 anchors and 41 header anchors per profile. No source text scoring,
inference crops, source mutations, label repair or per-case settings are used.
All four profiles process the full originals. Association is maximum parent IoU
at least 0.5 with stable first-index ties, independent of text and header labels.

| Profile | Complete topologies | Matched anchor keys | Preserved header anchors |
| --- | ---: | ---: | ---: |
| Native default | 7/7 | 74/74 | 0/41 |
| Source-tagged supported axes | 7/7 | 74/74 | 36/41 |
| Source-tagged logical parent | 7/7 | 74/74 | 36/41 |
| Source-tagged cell envelopes | 7/7 | 74/74 | 36/41 |

The captioned source falls back to native ruled extraction under every source-tagged
profile. Its geometry/topology survives, but five authored header roles are lost.
The other six source-tagged observations use the actual source-table method; no
native fallback is counted as source-tagged success. This identifies a caption
support gap rather than changing the references or hiding the fallback.

CompareReferenceTableTopology.java uses public factories and the existing validated
UpstreamTableCapture HTML topology parser; the helper name does not mean upstream
execution occurs. Header roles are evaluated separately. The runner verifies source
hashes/frames, its own identity, complete disjoint reference coverage and fresh output.
Typed whole-document failures retain all anchors. VerifyReferenceTableTopology.java
checks all 28 case/profile records, reference populations, association bounds and
arithmetic. Java 21 compilation and strict private Javadocs pass for both tools.
No production, model, OCR or dependency change is part of this baseline capture.

The initial manifest used descriptive local-render notes for two layout families.
render-qualification.json records their exact derivative hashes without changing
the frozen references or results. All five 2x5 renders have the same PNG hash.
These are source-author synthetic validation cases with visually inspected physical
labels, not independently human-reviewed acceptance, calibrated accuracy, full
PDF/UA compliance or proof of class-defined spans (these sources have no classes).

To reproduce, compile the two JVM utilities against the built Java 21 reactor and
validation-test classpath at the frozen production commit. Run
dev.docweave.validation.CompareReferenceTableTopology with manifest.json, the
pinned original source cache and a fresh report. Then run VerifyReferenceTableTopology
with the same manifest, report and a fresh receipt. Use headless AWT. No Java 25 run
is required during development.

## Explicit caption references

The candidate recognizes bounded explicit Caption nodes outside rows, resolves
their same-page references and requires their displayed text to be disjoint from
the real table envelope. Caption glyphs participate in source conflict checks but
remain unclaimed for native fallback. They are not copied into cells or labelled
as generated/semantic captions. Missing, reused, empty, alternate, cross-page or
overlapping content retains whole-table fallback. Limits are 16 caption nodes and
4,096 references, in addition to the existing shared source/page work limits.

The separately frozen caption-manifest.json retains every original source, anchor,
role, region, profile, parser and scoring rule. It declares only the three source
profiles on t01-pass-b as potentially changed. The candidate now extracts that
source table directly and preserves all five of its authored header roles:

| Source profile | Complete topologies | Matched anchors | Header anchors before → after |
| --- | ---: | ---: | ---: |
| Supported axes | 7/7 | 74/74 | 36/41 → 41/41 |
| Logical parent | 7/7 | 74/74 | 36/41 → 41/41 |
| Cell envelopes | 7/7 | 74/74 | 36/41 → 41/41 |

All 25 other case/profile observations remain exactly equal, including every native
observation. The complete-document character inventory excluding ASCII whitespace
is preserved for the captioned source. Its caption remains a separate native element
exactly once and is absent from table text; the numeric preservation check is saved.
These are geometry/role/preservation measurements, not exact cell-text or overall
document accuracy, full PDF/UA conformance or a diversity claim beyond three layouts.

Full Java 21 verification passes 1,125 tests with zero failures/errors and strict
Javadocs. Strict private Javadocs pass for the API, planner, ownership validator and
owned controls. Three new JVM controls cover direct/wrapped captions through all
declared quarter turns, preserved cells/text/geometry/outside/raster content, missing,
duplicate, redeclared, cross-page, alternate, empty, overlapping and cross-table shared
references, plus caption wrapper/reference budgets. Final focused source-table tests
also verify positive whole-source character preservation. No Java 25 run is added.

All 45 AMD and 39 Walmart observations remain exactly equal under the frozen business
regression manifests; 81/120 AMD and 105/105 Walmart exact rows are retained in their
explicit cell-envelope profiles. Source text/reference populations are unchanged.

The final JVM receipt verifies all 28 original records, 74 anchors and 41 header
anchors per profile. The allowed-change keys must exactly match the frozen manifest;
an attempt to exempt the native profile is rejected and writes no receipt. All
original baseline artifacts remain. This introduces no model, OCR, dependency,
default routing, beta publication or Central deployment change.
