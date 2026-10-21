# Source-declared soft-hyphen word wraps

An explicit new cell-text policy uses source structure semantics to remove discretionary
word breaks in supported tagged native table cells. It does not guess whether a hard hyphen
belongs to a word and does not substitute arbitrary alternate strings. Original source and
publisher labels are the same frozen files used by the preceding segmented-row baseline.

| Publisher control | Previous exact logical span/text | New exact logical span/text | Source discretionary joins | Header matches |
| --- | --- | --- | --- | --- |
| Original PLOS table | 135/136 | 136/136 | 1 | 9/9 |
| Second PLOS table | 13/15 | 15/15 | 2 | 3/3 |
| eLife body-span control | 20/20 | 20/20 | 0 | 3/3 |

Both actual Java21 and25 runtimes produce identical selected-page captures and numeric
scores. All21 other fixed regions are capture-equal to the previous baseline on both JDKs
(42 unchanged comparisons); all46 original source-frame records agree. This is a development
improvement on independently published logical cell labels, not held-out accuracy or physical
border truth. No reference transcriptions or source geometry enter extraction or change here.

The source audit found the physical word-split dash glyphs decode U+002D, but their exact
source structure ancestors contain ActualText of U+00AD. The implementation requires one
unambiguous structure alternative, exactly one page MCID and one retained '-' glyph. Competing
ancestor/descendant replacements, untyped dictionaries, MCR-level alternatives, multi-reference
or multi-glyph hints do not qualify. The glyph must uniquely end a native run, preceding a
nearby distinct-baseline run with letters on both sides. Rotated displayed text retains the
old joining. No dictionary or general word cleanup is used.

Select SOURCE_SOFT_HYPHENS_AND_WRAPPED_ISO_DATE through the existing source-tagged factories.
The new policy also preserves date joining; provider-only cells continue date joining alone.
Old factories/policies remain unchanged. Original cell budgets apply before shortening,
all original glyph claims and cell geometry remain, and combined native/provider limits still
apply. Changed tables report pdf_table_joined_soft_hyphens and a scoped text-policy marker.
Chunk fragments drop whole-table counts; original-element provenance keeps them.

Three new owned methods cover valid direct/MCR references, original geometry/HTML, old-policy
preservation, conflicting/multiple/untyped alternatives, wrong physical boundaries, rotation
and original cell budgets. The existing chunk round-trip/provenance test now covers the new
count. Full994-test clean verify and strict public Javadocs pass on both actual JDKs; strict
private Javadocs cover changed source helpers and the new test. See verification.json.

Reproduce whole-original captures with CaptureNativeModelTables using the same frozen
source cases/model hashes as ../pdf-segmented-row-spans, native-first and uniformly selected
native tagged.first, segmented.rows and source.soft.hyphens properties. The native source
soft-hyphen property selects the combined cell policy and requires tagged precedence.
Scoring PdfScientificSpanTest requires scientific.tagged.first, scientific.segmented.rows
and scientific.source.soft.hyphens=true plus the same publisher cache/captures/manifest/report
properties. All selected models execute normally; no OCR or source-reference regions are
supplied to extraction. Raw captures/documents remain off-Git.

Official upstream freshness reports the already-reviewed0.27.28 release/main1b02b4d drift,
six commits beyond the historical reference. The strict freshness assertion intentionally
fails rather than silently rebasing historical0.27.27 goldens; the receipt is retained here.
Ambiguous wrapped headers, scan segmentation/recognition, broader logical reading order,
calibrated confidence and the final comparison matrix remain open. Release is deferred.
