# Explicit horizontal-space caption cues

`IMAGE_ADJACENT_HORIZONTAL_LABELS` is a separate opt-in strategy sharing the
existing bounded image geometry with `IMAGE_ADJACENT_LABELS`. It recognizes tab
or Unicode Space_Separator characters around the label tokens, including source
U+2002 EN SPACE. No source character is rewritten. The original ASCII policy,
provenance, defaults and geometry thresholds remain unchanged.

The new policy records `image_adjacent_horizontal_label_v1`. It excludes vertical
line/paragraph separators and whitespace-only bodies. Pattern instances are
immutable and shared; matchers and output state remain call-confined.

## Complete development-source results

All ten complete publisher captions, three full originals, three uniform profiles
and 18 original logo/Alt/ActualText controls remain fixed. These are previously
evaluated development sources, not a held-out or human-reviewed corpus.

| Uniform profile | ASCII policy: complete bodies classified | Horizontal policy: complete bodies classified | Strict entire label+caption unit equality |
| --- | ---: | ---: | ---: |
| Native lines |0/10|6/10|0/10|
| Native column paragraphs |4/10|10/10|4/10|
| Source-tagged tables / native lines |0/10|6/10|0/10|

All six PLOS captions now classify under every profile. The four eLife captions
remain partial leading units under line profiles and complete units under column
paragraphs. Missing complete line-profile captions receive no complete-body credit.
All nine runs succeed. Full all-text results, hashes, order measurements and
caption reference identities remain exactly equal to the frozen baseline.
The original ASCII policy's complete nine-record result tree reproduces exactly.
All18 negative observations retain one decodable75x16 image, unchanged PNG hashes
and zero fabricated captions. Those negatives are metadata variants of one layout.

Strict entire label-plus-caption equality remains **4/10**, not10/10. For all six
PLOS units, a supplemental whole-unit diagnostic explains only publisher label
presentation: JATS lacks the terminal label period and the PDF uses EN SPACE
after it. Every caption-body character is exact under the existing ASCII-only
whitespace normalization, with no extra body text. The diagnostic neither edits
output/references nor awards strict equality credit. Its five owned controls reject
extra body text, changed labels, vertical breaks and changed body punctuation.
The complete strict projected result tree remains exact after adding those fields.
Four partial eLife line units remain unexplained as complete units and receive no
presentation-only credit. No fuzzy text matching or per-source production routing
is introduced.

This is a measured caption-classification improvement on the declared population,
not general image association precision, caption-box accuracy, vector figure
coverage or PDF completion. Further diverse transfer and held-out review remain.

## Validation and reproduction

The fourth owned caption test covers ASCII/tab, NBSP, OGHAM SPACE MARK, EN/EM,
narrow nonbreaking, medium mathematical and ideographic spaces. It verifies source
text/geometry preservation, original ASCII rejection of non-ASCII separators,
vertical/zero-width separator and whitespace-only body rejection. Existing native,
tagged, ambiguity, cap, interruption and immutable policy controls remain intact.
Full offline Java21 verification passes **1,159 tests**, zero failures/errors/skips,
with strict public Javadocs. Changed production/test code and the supplemental
diagnostic pass strict private Javadocs. Pinned formatting and diff checks pass.
Java25 and publication remain deferred; no new dependency or Python test is added.

The manifest freezes the new explicit policy, original sources, unchanged geometry,
baseline and helper code before evaluation. Configuration-only patches create
scratch copies of the same JVM positive/negative/strict-unit helpers; tracked
baseline sources remain unchanged. Compile the copies on Java21 against the
validation test classpath and run the same full-source invocations documented in
the earlier caption evidence, using fresh report paths and headless AWT.

For the separately declared post-primary label-presentation diagnostic, apply
`label-presentation-diagnostic.patch` to a fresh copy of the tracked
`AuditCaptionUnitBoundaries.java` (rather than the already patched copy), compile
it alongside the copied scorer, and run the same complete unit-audit invocation.
The patch retains all strict fields and adds explicitly separate diagnosis only.
It does not change extraction, references or the acceptance metric. Complete
reports, patches and both protocols accompany this README. Original PDFs, rasters,
weights and caption transcriptions are not redistributed.
