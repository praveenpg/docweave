# Frozen-code Scientific Reports caption transfer

The source was selected before any DocWeave extraction: first result hosted by PMC
in the recorded Scientific Reports2026 search. Code is frozen at `4e4ac27`, source
and evaluation protocol committed at `cc4a03d` before scoring. All five main-body
publisher figure captions remain in every profile's denominator.

The original nine-page published-version PDF and matching XML come from the
official PMC Article Datasets public distribution, version1, same selected DOI.
Dataset metadata records open access, not manuscript, not retracted, CC BY-NC-ND;
the XML license URI is `https://creativecommons.org/licenses/by-nc-nd/4.0/`.
Both distributed MD5 values, byte counts and SHA256 identities are retained.
Raw sources, article text, figures and rendered pages are not redistributed.

## Complete results

| Uniform profile | All-text complete bodies, either policy | NONE complete bodies classified | Numbered-title complete bodies classified |
| --- | ---: | ---: | ---: |
| Native lines |4/5|0/5|0/5|
| Native column paragraphs |4/5|0/5|2/5|
| Source-tagged tables / native lines |4/5|0/5|0/5|

All six whole-original extractions succeed. The new policy classifies two leading
units in each profile; line units receive no complete-body credit. Full text,
reference identities, outcome and order measurements remain exactly equal between
policies in all three profiles. The fifth caption body does not match in full text
under either policy; it is a retained strict miss, with no cause or repair assumed.

Read-only eligibility attribution retains every Fig/FIG-prefixed source unit and
same-page image relation. Three caption prefixes fail the current label predicate.
Two also fail its left-edge alignment:31.21 and39.73 points rather than the allowed12;
both have vertical gaps near12.68 points. The other has one eligible image.
Independent Poppler layout extraction shows horizontal space between each of these
three caption numbers and its terminal period. These are formatting/geometry
observations, not permission to widen thresholds or change the frozen score.

This one new-publisher transfer is not a human-reviewed held-out acceptance set or
physical caption-box/association precision measurement. Its frozen failures remain
even if subsequent development uses this now-observed source. Earlier four-original
development results are not silently expanded or relabelled as this transfer.

## Reproduction

Source-only `InventoryPublisherFigures` uses the same one-case population patch
as the prior PeerJ transfer; all original bytes are verified before secure parsing.
The unchanged three-profile scorer uses the PeerJ transfer configuration plus the
numbered-title option patch recorded in the evaluation protocol. Run twice with
headless AWT, explicit manifest/cache and fresh report paths; set
`docweave.caption.transfer.titles=true` only for the candidate. Presentation-form
normalization remains false. `eligibility-configuration.patch` selects the committed
title predicate in the prior read-only eligibility helper, without modifying geometry.

`CompareTransfer.java` takes baseline report, candidate report and a fresh output,
using the Java21 Jackson/runtime classpath. It checks exact JSON trees for every
outcome/reference/text field across all three profiles. Standalone helpers compile
and pass strict private Javadocs. This evaluation changes no production code;
the preceding full Java21 checkpoint remains1,164 passing tests. Java25 and
publication remain deferred. Initial metadata fetch hit sandbox DNS failure and
succeeded with network access; no source was substituted after that failure.
