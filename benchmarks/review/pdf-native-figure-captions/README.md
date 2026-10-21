# Explicit image-adjacent native captions

`PdfOptions.withFigureCaptions(IMAGE_ADJACENT_LABELS)` selects a hidden stateless
strategy through `PdfFigureCaptionDetection`. `NONE` preserves default categories.
The previous 13-argument constructor remains available; every immutable copy
method retains the new policy. No OCR/model/provider discovery is introduced.

The strategy changes only an existing eligible native unit's type and adds
declared heuristic provenance plus its associated image's displayed bounds.
Source text, geometry, order, table ownership and image output are retained.
Central IDs use the resulting selected type. Caption continuations are not joined.

Strict English Fig/Figure labels require a positive number of at most three
digits, optional suffix letter, period, horizontal space and following text.
Images must be at least36x18 points. Vertical gap is0..24, left-edge difference
at most12 and overlap at least half the narrower width. Every eligible image/cue
relation counts toward ambiguity: only unique one-to-one relations classify.
Rotated metadata, more than1024UTF-16 cue units, vector-only/unlabelled content
and unsupported labels retain ordinary output. Fixed caps4096pageunits/128images/
256cues fall back whole. Shared geometry work and interruption remain bounded.
Thresholds are heuristic rules, not calibrated confidence or semantic guarantees.

## Complete original population

The fixed publisher baseline retains all ten full captions from three complete
original PDF/JATS pairs. These are prior development sources, not held-out human
review. Every case/profile, missing caption and typed failure stays in the report.

| Uniform profile | Baseline complete classified captions | Candidate complete classified captions | Entire label+caption units exactly equal |
| --- | ---: | ---: | ---: |
| Native lines |0/10|0/10|0/10|
| Native column paragraphs |0/10|4/10|4/10|
| Source-tagged tables / native lines |0/10|0/10|0/10|

The four exact complete units are all eLife captions. No extra prefix/suffix or
unrelated text is accepted by the entire-unit audit. Line profiles emit four
partial leading eLife units and receive no complete-caption credit. All six PLOS
captions remain unclassified under every profile; they are not omitted or repaired.
All complete source captions remain uniquely present in all-text output under
every profile. Full all-text hash/presence/order results and reference identities
are exactly equal to the baseline across all nine case/profile runs.

All18fixed logo/Alt/ActualText negative controls succeed with one decodable75x16
image and zero fabricated captions. PNG hashes remain exact. These are three
metadata variants of one rendered layout, not diverse negative coverage.

The result demonstrates a limited opt-in classification improvement. It does not
establish general caption bounds, image association precision, vector figure
support, full scientific-document caption coverage or a default-policy change.

## Validation and reproduction

Three owned JVM tests cover real native/source-tagged extraction, original-unit
preservation, strict cues, rotations, small/distant/shifted images, multiple images
and competing cues, fixed caps, interruption and old-constructor/copy behavior.
Full offline Java21 verification passes1,158tests with zero failures/errors/skips,
including strict public Javadocs. Strict private Javadocs pass for all changed
production/newtest code and the whole-unit audit. Pinned formatting/diff checks pass.
Java25 and publication remain deferred. No new dependency or Python test is added.

Before primary evaluation, the manifest froze the rule, sources, baseline, full
denominators and evaluated code. No threshold changed after evaluation. The two
recorded patches insert only the explicit configuration call into copies of the
already frozen JVM baseline helpers outside the repository. Their references,
scoring and extraction logic otherwise remain identical. This avoids changing
the historical baseline tool or silently repinning its evidence.

Copy `ScorePublisherCaptions.java` and `VerifySourceFigureImages.java` to an explicit
scratch source directory. Apply their respective recorded option patches there,
then compile on Java21 with the validation module test classpath. Run the complete
positive and negative invocations documented in their earlier evidence directories,
using fresh reports and headless AWT. Do not modify the tracked baseline helpers.

For the separately declared stricter unit audit, compile
`AuditCaptionUnitBoundaries.java` with the copied baseline helper on the classpath.
Supply the original scientific-reading manifest, original cache, frozen caption
baseline report and a fresh output. The audit reuses the frozen serializer via
reflection and verifies every complete caption reference hash before extraction.
It requires an entire classified unit to equal the original label plus caption;
it never chooses a better text alignment or changes extraction. Nonexact partial
units are reported explicitly. Complete reports and both protocols accompany this
README. No source PDFs, rasters, weights or transcriptions are redistributed.

### Attribution of the six PLOS misses

A subsequent whole-original numeric audit reuses the committed private cue and
adjacency predicates. All six PLOS units have exactly one size/geometry-eligible
image, but their label predicate is false: the separator after `Fig N.` is
U+2002 EN SPACE (decimal8194). eLife uses ASCII SPACE (decimal32) and its four
cues pass. All nine original/profile cases remain in the audit. Independent
Poppler image inventory confirms substantial PLOS rasters, so these misses are
not attributed to vector-only figures or widened image-gap thresholds.

The initial audit inspected matching cues only; the read-only expansion retains
all Fig/FIG-prefixed native units and records the actual label predicate and
separator codepoint. Tool identities and the scope-text correction are recorded
in the audit protocol. No product predicate or reference changed. This diagnostic
supports investigating a separately explicit Unicode-horizontal-space cue while
preserving the existing ASCII policy, original characters and strict scores.
