# Explicit numbered figure titles

`IMAGE_ADJACENT_NUMBERED_TITLES` adds bare numbered labels followed by an uppercase
or titlecase character, and colon-delimited labels. Period-delimited labels remain
supported. TAB/Unicode Zs separators, positive numbers1..999 and optional label
letters follow the explicit contract. Source text is never rewritten. Geometry,
ambiguity rejection, caps, cancellation and default policies remain unchanged.
Provenance is `image_adjacent_numbered_title_v1`.

## Measured development population

The manifest froze production/scorer identities before extraction. All three
earlier scientific originals and the previously evaluated PeerJ published-version
PMC distribution remain included. These are development cases, not a new held-out
sample. References and exact ASCII-whitespace-only scoring remain unchanged.

| Uniform profile | Horizontal policy complete bodies | Numbered-title policy complete bodies |
| --- | ---: | ---: |
| Native lines |6/16|7/16|
| Native column paragraphs |10/16|14/16|
| Source-tagged tables / native lines |6/16|7/16|

All six PeerJ leading caption units now classify under every profile, previously
zero. Complete-body credit is1/6 in line profiles and4/6 in columns. Two PeerJ
caption bodies retain printed line-wrap hyphens absent from XML; independent
rendering attribution is retained in the prior transfer evidence. They still fail
strict matching. Partial leading units receive no complete-body credit. No source
or reference repair, model threshold tuning or source-specific routing is used.
The previously negative whole-page Caption-class model trial remains negative;
this feature does not expose or depend on that model.

All nine previous positive result records remain exactly equal, not merely their
aggregate counts. Full text, order, hashes and reference identities are exact in
all12 original case/profiles. The historical horizontal policy reproduces all nine
previous records exactly. All18 logo/Alt/ActualText negative records reproduce
exactly: zero captions and unchanged decoded PNG identity. Those negatives are
three metadata variants of one visual layout, not18 independent documents.

This measures classification and complete-body presence on a declared population.
It does not establish physical caption-box accuracy, broad association precision,
unlabelled/vector figure understanding, semantic confidence or PDF completion.
Strict entire publisher-label-plus-body equality is a separate earlier metric;
this study does not turn the body-presence result into whole-unit equality credit.

## Validation and reproduction

All five owned caption tests pass. The added test covers bare and colon titles,
Unicode horizontal characters, unchanged source text/bounds/provenance, lowercase
prose references, invalid ordinals, vertical/zero-width separators and unchanged
original cue policies. The full Java21 Maven verify and strict private Javadocs
pass. Java25 verification remains deferred. No new dependencies or Python tests.

The initial image-negative command omitted headless mode and failed while opening
X11; the explicitly headless replay succeeds. That environment failure is not an
extraction accuracy result. No source case was removed.

Use the existing historical helper copies reconstructed from the horizontal-cue
and PeerJ transfer configuration patches, then apply this directory's three
configuration-only patches. The reference parser, scorer and source populations
are unchanged. Compile each scorer population to a separate directory with the
same Java21 reactor/runtime classpath. Run `ScorePublisherCaptions` with the
scientific-reading manifest and original three-source cache, and separately the
PeerJ source manifest and original PeerJ cache. The latter uses
`-Ddocweave.caption.transfer.titles=true`; presentation normalization stays false.
Run `VerifySourceFigureImages` with the qualified source-figure manifest and cache,
using `-Djava.awt.headless=true`. Every report path must be fresh.

`CompareCaptionReports.java` checks exact result-tree invariants with arguments
`repository-root experiment-directory NEW-report`. The experiment directory holds
`old.json`, `peerj.json`, `negative.json` and the historical-policy replay
`horizontal-regression.json`. Its numeric output is committed as
`comparison-java21.json`. Standalone source launch uses the same Jackson/runtime
classpath. All source hashes are inherited from the pinned input manifests;
raw source files, transcriptions, rendered pages and model assets are not committed.
