# Disjoint paragraph-continuation attempt — not promoted

The retained eLife first-page body lines are interrupted in output by sidebar/copyright
text. A source render and existing coordinate capture confirm those runs already have
separate boxes; no source-gap threshold correction is justified by this counterexample.

One isolated candidate changes paragraph grouping, without another gutter search. Under
explicit column layouts only, it allows the existing paragraph-join predicate to continue
past intervening horizontally disjoint ordinary runs. It retains the original vertical,
height, alignment, metadata and list guards, a 64-item lookahead and the shared work bound.
Overlapping or nonordinary elements stop continuation. Source text is retained.

| Original source | References | Native exact unique, before -> candidate | Explicit assembly, before -> candidate |
| --- | ---: | ---: | ---: |
| Recent PLOS | 33 | 4 -> 4 | 24 -> 24 |
| Next PLOS | 93 | 54 -> 54 | 83 -> 83 |
| eLife | 17 | 1 -> 1 | 1 -> 1 |
| Total | 143 | 59 -> 59 | 108 -> 108 |

All six calls process the complete originals. All 143 publisher paragraphs and 4,942
within-document reference pairs remain per profile; nine duplicated references on the
second PLOS source remain. No matched pair is reversed. Exact scoring and source references
are unchanged. The existing [baseline](../pdf-disjoint-gutter/java21.json) and
[candidate report](java21.json) retain individual outcomes and hashes. Original PDF/XML
byte counts and hashes are independently checked against the frozen full-body manifest.

**Decision: park the candidate.** There is no exact-paragraph gain on the original documents,
so no new public option or production grouping change follows. Complete-paragraph misses
also include physical hyphens, NBSP and publisher/PDF differences; they are not all ordering
failures. This does not claim that every source character or visual order has been validated.

The [unapplied compressed patch](candidate.patch.gz) applies to development baseline
`ebaae9d`; it is not shipped runtime functionality. Reproduce in an isolated copy with the
full-body manifest and existing Java scientific reference/scoring helper, running native
compound columns and explicit source-line-wrap/page-fragment assembly uniformly on all three
originals. [Receipt](receipt.json) pins the protocol and candidate report. No model, OCR,
Python evaluation, full build or Java 25 run was needed for this rejected experiment.
Return with an independently supported ordering counterexample and a different approach.
