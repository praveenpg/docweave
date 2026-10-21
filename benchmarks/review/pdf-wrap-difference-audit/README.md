# Paragraph differences after physical-hyphen wrap policy

This is a JVM diagnostic on production commit `55a544e`, using the same four complete
scientific originals and strict publisher XML reference paragraphs. The separately frozen
protocol adds physical-hyphen wrap assembly alongside native lines and the earlier soft-hyphen
assembly. No extraction uses reference text, no scoring normalization changes and no best
profile is selected. All 84 paragraph/profile observations remain, including unlocated spans.

Both actual Java21 and Java25 produce identical numeric diagnostics and unchanged strict
baseline scores for all12 case/profile rows. `validation.json` was produced by the existing
`pdf-scientific-text-differences/VerifyTextDifferences.java` with this protocol and paired reports.
The extended audit also reruns the original two-profile protocol unchanged and agrees exactly
with the historical report after removing runtime identity. No old evidence was rewritten.

| Original | Prior assembly exact | New assembly exact | Located nonexact diagnostic edits, prior / new |
| --- | ---: | ---: | ---: |
| recent |7/9|8/9|2 /1|
| next |12/12|12/12|0 /0|
| elife |0/4|0/4|28 /28|
| holdout |0/3|0/3|1337 /1336|

Edit totals describe uniquely anchored diagnostic spans only; they are not whole-document
error counts or accuracy. Holdout retains one unlocated reference per profile and103 visited
optimal-predecessor ties under either assembly policy. Do not infer causal ownership from
those edit paths. eLife retains two unlocated references per profile; its located traces
include21 space/NBSP substitutions and7 insertions (including physical hyphens and spaces).

The recent remaining strict miss is ordinal7: expected608 versus candidate609 code points,
with an extra ASCII space at494. Local source inspection identifies page3/source paragraph18;
the original native line endpoint contains explicit whitespace and does not meet the unique
hyphen endpoint guard. The new policy deliberately preserves this source whitespace. No
production change is justified by removing it merely to match publisher XML.

A separate local native-glyph count confirms that eLife contains both ASCII spaces and NBSP
in its PDF (for example, page1 has500 and3 respectively). This is preliminary source evidence,
not attribution of every one of the21 trace substitutions. Native Unicode distinctions should
not be flattened to make strict reference scores pass. Further work needs glyph-level mapping
of the located spans and structural attribution of the unlocated references.

Local source-span tracing gives the following additional diagnostic observations under the
new assembly policy. Counts include every non-image element overlapping the uniquely anchored
span; lengths are UTF16 units. These do not establish which individual elements are erroneous.

| Source/reference ordinal | Reference / candidate span units | Overlapping elements | Source-tagged paragraph elements | Pages |
| --- | ---: | ---: | ---: | --- |
| elife/2 |1854 /1856|19|0|2|
| elife/3 |1853 /1858|20|0|2|
| holdout/0 |1329 /1848|24|4|1|
| holdout/1 |1999 /2799|29|3|2|

All these elements are `UncategorizedText`. The other eLife two references and holdout one
remain unlocated. The holdout spans combine source-tagged and fallback text, so a global
hyphen/space rewrite cannot address their structural differences. Source paragraph ordinals
observed in those spans are182–185 and187–189. The next structure investigation must inspect
the original page layout and ownership of the fallback text, without dropping elements merely
because they do not occur in the publisher XML paragraph.

Further local geometry inspection identifies a concrete rejection mechanism. The original
holdout page1 has a wide abstract to the right of a narrow publication-metadata sidebar.
Source paragraph10 owns1332 glyphs but becomes19 geometric runs, including three3-character
raised runs with height about3.34 versus body height about5.73 and baseline displacement3.33.
These short runs make the common horizontal line intersection negative (about-97.87 units).
The existing paragraph preparation rejects it. Page2/source paragraph12 similarly owns2005
glyphs, produces26 runs and has a negative intersection (about-46.68 units). Both have complete
declared source references in the isolated preparation probe and no close-baseline rejection.
That probe makes all original glyphs available; it does not reproduce prior table claims or
the later shared-ownership pass. No production guard was changed from these observations.

The bounded follow-up is source-owned inline superscript/subscript grouping, with explicit
ambiguity, adjacent-row and column controls. It must retain original glyph identities and
coordinates and must not hide sidebar material. A successful fix needs fresh uniform runs on
all four originals and owned script/non-script regressions, rather than an exception keyed to
these source paragraph ordinals.

Standalone compilation and owned diagnostic self-tests pass on both JDKs (one compatibility
test per JDK); strict private Javadocs pass for the extended helper. The full1,061-test clean
verification on both JDKs belongs to the unchanged production baseline, not a newly repeated
full run for this tool-only extension. No Python test/dependency, beta candidate or publication.
