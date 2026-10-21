# Scoped retained-excerpt attribution review

Scope:13existing anchors/376Unicode code points in two corpus manifests, covering four
pinned DOCX files and three publishers. This checkpoint verifies the concrete redacted
candidate's unchanged manifests, not every external source referenced anywhere in the project.
The [review record](review.json) contains document/page identities, anchor hashes and bounds.
The [consolidated source-only notice](../../SOURCE_EXCERPT_ATTRIBUTION.md) retains attribution
and official source/licence links without copying additional excerpts.

## Findings

All four currently published pages retain the expected DOCX download links and OGLv3.0/Crown
copyright statements. Existing graphic/other-stated exceptions remain applicable. The official
National Archives licence text was retrieved with curl after browser HTTP403 and checked for
acknowledgement/link requirements, exclusions and non-endorsement. Scottish Government's
separate copyright guidance distinguishes third-party rights. Primary URLs and retrieved-page
SHA256 fingerprints are recorded; raw pages/source binaries remain in a local fetch-only cache.

All four documents match their recorded SHA256 and byte lengths. All13anchors occur in
pinned main-body XML after adjacent Word text runs are concatenated per paragraph and
whitespace is normalized. An initial helper inserted spaces between formatting runs; the
corrected final check preserves adjacency and passes all anchors. No production fix, changed
anchor, replaced golden or failed pilot is counted as a passing result. This presence check
is not Word rendering, typography, full-text correctness or new parser accuracy evidence.

Publisher statements and recorded attribution are consistent for this narrow selection.
The record does not adjudicate every underlying third-party right or constitute independent human
approval. The corpus manifests' existing human_review=pending values remain unchanged.
No completed respondent data, source graphics, original DOCX or full extracted report text
is committed. A generic form question is a retained source anchor, not submitted personal data.
No inference that all public-sector material or all corpus documents are freely redistributable
is made from a website footer alone.

## Candidate attachment and verification

A local attribution supplement is bound to the redacted-v1 archive and full-manifest hashes.
It sits OUTSIDE that immutable source/archive: no projected files, ledger hashes or previous
build receipts were rewritten. The consolidated notice and review record must be incorporated
in a newly hashed candidate before it is selected for publication. Existing candidate holds
remain historical; the supplement records this narrower completed provenance check.

Manifests and all13anchor hashes match both the main source and concrete candidate. The
snapshot/projection archives and all original/projected file hashes remain unchanged. Changed
Markdown links and safe review JSON are checked. This checkpoint changes only source-only
documentation/evidence, not Maven configuration, packaged attribution resources, Java APIs or
runtime extraction. Prior Java21/25 899-test/Javadoc and candidate evidence remains separately
scoped; no new full local Maven run, external engine test or PDF quality gain is claimed.

Next release work can consolidate the source candidate with this notice and stage an unsigned
beta bundle for review. Release approval, wider evidence rights, final version/signatures,
namespace/Portal and explicit publication/upload instruction remain open. PDF remains roughly
85–90% complete by subjective feature planning, not measured accuracy or release readiness.
