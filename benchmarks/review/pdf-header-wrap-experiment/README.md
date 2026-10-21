# Native header-wrap geometry experiment

**Decision: do not adopt this merge hypothesis as a production policy.** Its fixed strict setting
misses the known wrapped header, and spacing permissive enough to admit that header also admits
legitimate distinct data rows. No production code/default/API/model or source label changes.
The previously measured2/214 and208/215 span/text results stay unchanged.

## Frozen experiment and results

The manifest was frozen before native feature extraction. Read only source identity/page and
unchanged model bands from the prior numeric audit, then extract native positions and painted
rules from the pinned original us-002.pdf. No reference strings, row labels, reference ROI or XML
are supplied to the hypothesis. Both pages use one configuration on actual Java21/25. The
prior model geometry is replayed; models are neither restored nor executed in this experiment.

A pair is eligible only when both adjacent bands have nonblank glyphs, each contains a single
baseline within2points, native median heights agree within10%, horizontal envelopes overlap
at least80% of the smaller width, positive baseline separation is at most2times the larger
median height, and no overlapping horizontal source rule lies between the baselines.
All native positions in the vertical bands are considered; this research probe has no inferred
column ownership or automatic header detector. It requires upright unit-scaled zero-origin pages.

| Observation | Page1 weak table | Page3 control |
| --- | --- | --- |
| Boundaries evaluated |32 |31 |
| Eligible under frozen hypothesis |0 |0 |
| Wrapped header baseline separation |12.12030029296875 |not a target |
| Wrapped header median native glyph height |4.706099987030029 |not a target |
| Target separation/height ratio |2.575444705036483 |same ratio in several distinct body pairs |

The recorded target boundary is model rows1/2. Native median height is PDFBox's glyph metric,
not nominal font size or the original XML's text-envelope height. Its two lines pass the other
cues but fail the fixed2x distance gate. Simply substituting a larger threshold is unsafe:
page1's legitimate body pairs often have an even smaller ratio around2.2694, and page3 pairs
3/4,4/5 and others have **exactly the same ratio** as the target while passing the other cues.
These are post-run diagnostic observations, not a second tuned experiment or corrected score.
A minimum/maximum gap threshold or unchanged common-style cues cannot separate these examples.

Synthetic controls also demonstrate that wrapped and valid stacked subheaders can have identical
metrics; geometric agreement is not proof of semantic row identity. Source-rule veto, empty-band
and large-gap controls remain. The research gate's conservative rejection is not a production
feature or a positive-quality test. Neither unchanged table fidelity nor overall PDF completeness
is claimed. The older fixtures remain development evidence, not recent business/scientific coverage.

## Reproduce

Fetch the original PDF using the URL and SHA256 pinned in the independent-source manifest. Compile
ProbeHeaderWrapGeometry.java with the project runtime classpath, then run on both JDKs:

```sh
java -Djava.awt.headless=true -cp "$CLASSPATH" \
  dev.docweave.partition.ProbeHeaderWrapGeometry \
  /source-cache/us-002.pdf \
  benchmarks/review/pdf-education-row-audit/education-first-java21.json \
  /absolute/NEW-report.json
```

Repeat with education-second-java21.json. Both JDKs deliberately replay the same frozen numeric
model geometry. Byte/parsed native reports agree. The helper verifies the original PDF hash,
reads only model bands and source identity, uses bounded PDF glyph/operator traversal and shared
geometry work/cancellation checks, limits each input16MiB, rejects unsupported frames and invalid/
overlapping/unsorted bands, and requires a new output path. No source strings appear in reports.
Full verification passes950 tests on each actual JDK21/25, strict Javadocs and formatter/diff
checks. The verification workspace also contains a concurrent multi-catch refactor in the delimited
parser; that unrelated edit is preserved and excluded from this change.

No whole-document extraction/model/table accuracy run occurs here; ordinary verification compiles
the standalone helper for release21 and runs bounded source/model-free controls.

The verification environment reused an existing Maven cache read-only and downloaded the official
Corretto21 JDK and the pinned public source, and extracted the cached formatter23.1.1 into isolated
temporary directories. No system JDK or Maven settings were changed. Numeric metrics and source/
code hashes are committed; PDF bytes, source strings and caches are not.

Fresh official upstream metadata still reports0.27.28/main1b02b4d versus the historical0.27.27pin.
The opt-in strict release assertion fails for this already recorded drift; this is not a freshness/
parity pass. No pin or historical golden was rewritten. Release preparation remains deferred.

Next: evaluate model-declared header-region evidence and token-assisted structure refinement,
with the now-proven ambiguous pairs retained as negative controls. Do not add a generic gap-based
row merge or select a policy using reference text. Any successful alternative must replay the
fixed source set and preserve legitimate header/data rows before production adoption. Recent-source,
historical/degraded scan text, calibration and final comparison gates remain open.
