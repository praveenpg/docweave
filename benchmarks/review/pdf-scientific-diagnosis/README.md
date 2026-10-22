# Scientific full-body miss diagnosis

Diagnosis of the frozen full-body population
([manifest](../pdf-scientific-full-body/manifest.json): 143 publisher paragraphs, 4,942 order
pairs per profile, three whole PLOS/eLife PDF/JATS pairs). No runtime code changed. Scoring,
references and the frozen four-profile schedule are unchanged.

## Reproduced baseline (`baseline-java21.json`)

| Profile | Exact unique paragraphs | Preserved pairs | Reversed |
| --- | --- | --- | --- |
| LINES | 58/143 | 1,384/4,942 | 0 |
| COLUMN_PARAGRAPHS | 58/143 | 1,384/4,942 | 0 |
| COLUMN_PARAGRAPHS_COMPOUND_WRAPS | 59/143 | 1,437/4,942 | 0 |
| SOURCE_PARAGRAPHS_LINE_WRAPS_ASSEMBLED | 108/143 | 3,679/4,942 | 0 |

The eLife PDF has no structure tree, so its tagged/assembled output is byte-identical to LINES.

## Miss taxonomy

A diagnostic walk compared each missing reference paragraph with the candidate text from its
longest exact prefix onward, tolerating only (a) a source NBSP where JATS has an ASCII space and
(b) a physical hyphen absent from JATS, and reported the first remaining divergence. This walk
is a classification aid; it is never used as a score.

| Profile | Case | Presentation only | Structural |
| --- | --- | --- | --- |
| Compound columns | recent / next / eLife | 13 / 19 / 5 | 16 / 11 / 11 |
| Assembled | recent / next / eLife | 0 / 0 / 5 | 9 / 1 / 11 |

Structural divergences, by inspection of every reported break: first-page metadata sidebar lines
interleaved into abstracts (all three sources); running header/footer lines between the two
page halves of a paragraph; figure/table captions between paragraph halves; superscripts emitted
before their baseline line (`(I2` → `2 ... (I`); and `self- efficacy` in tagged PLOS output.

### `self- efficacy` is a source ambiguity, not a join bug

Source glyph inspection of the recent PLOS PDF shows typesetter discretionary breaks end with a
bare hyphen (`investi-`, `tradi-`), while a hard hyphen at a line break is followed by an explicit
space glyph (`self-` `U+0020`). The next PLOS PDF encodes the legitimate suspended hyphen
`low- and` identically. The existing `SOURCE_LINE_WRAPS` guard that keeps explicit edge
whitespace is therefore correct; separating these cases needs lexical knowledge, which the
policy deliberately excludes.

### eLife

11 of 16 eLife misses first diverge at a source NBSP (`et al.`, `Figure 3`, `95% CI`) where JATS
has an ASCII space; four more at physical hyphens. NBSP is preserved by the frozen rule.

## Bounded checks

- Repeated page-furniture ceiling (diagnostic only, deletes repeated top/bottom-margin lines found
  on at least three pages, then rescores): LINES 58→61, compound columns 59→62, assembled 108→108.
  This ceiling is too small to justify a furniture reordering policy; parked.
- Existing explicit `InlineScripts.NEARBY_SMALL_GLYPHS` (no code change), scored with the frozen
  evaluator through the additive `inline-scripts-manifest.json` (same cases/pins, one appended
  profile): **113/143** exact paragraphs and **3,864/4,942** preserved pairs, 0 reversed, versus
  108/143 and 3,679 for the current assembled profile (`inline-scripts-java21.json`). This is
  configuration evidence for an existing option, not a new extraction capability.

## Remaining / next bounded action

The first-page sidebar interleaving is the main remaining structural class; two algorithmic
attempts are already parked. Next action: an independently reviewed visual-order packet for the
three page-1 sidebars before any third algorithm. Captions between paragraph halves and furniture
remain parked with the ceiling above.


## Integration review

A fresh Java 21 replay using the additive inline-script manifest reproduces every result
for all three complete papers and all five profiles (15 records), checked by exact JVM
JSON result-array equality. The existing explicit configuration retains 113/143 exact
paragraphs and 3,864/4,942 preserved pairs. No scoring normalization or scientific runtime
change is integrated. The miss taxonomy is a diagnostic aid, not independently reviewed
paragraph/reading-order truth; title/sidebar and reference-presentation questions remain.
Fresh capture and verification identities are retained under integrated names.
