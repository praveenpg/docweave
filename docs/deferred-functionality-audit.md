# Deferred-functionality audit — 2026-10-15

## Parked PDF reading-order investigation — 2026-10-20

Status: backlogged; revisit after other open issues have been addressed.
The complete-body scientific check exposes an eLife page1 sidebar/copyright stream interleaved
between two body lines. Retain all source text and the original full143paragraph reference
population. First-difference inspection also finds physical source hyphens and NBSP/reference
differences, so incomplete exact paragraphs cannot all be attributed to reading order.
Initial diagnosis did not change production order. After the intervening graphics,
margin and list-boundary fixes, one explicit disjoint-projection candidate was tested.
It passes owned unequal-leading controls but produces no complete-paragraph gain on
any of the three originals: native59/143 and assembled108/143 remain unchanged.
The candidate was removed from production; see the [retained rejected attempt](../benchmarks/review/pdf-disjoint-gutter/README.md).
Do not repeat this projection/threshold search.

Evidence: [complete-body comparison](../benchmarks/review/pdf-scientific-full-body/README.md),
with pinned original PDFs/XML, all source/reference populations and unchanged legacy scores.
Next bounded action when resumed: isolate a different source-supported ordering
counterexample before another implementation attempt; the owned uneven-leading
projection candidate did not improve the original documents. Preserve sidebar text, source characters, defaults and all
failed/missing/duplicate references. If no clear gain, retain the counterexample and move on.

Investigation policy: one proposed fix and one focused validation pass per investigation;
park an unproductive item, address the other open issues, then return. A measurement-only
step is not an extraction improvement.

Scope: current repository implementation, public options, diagnostics, tests, compatibility notes and saved evidence at executable baseline `418c6ff` (documentation checkpoint `44ac2c2`). This is a local audit, not a new upstream release comparison or a completeness percentage. Priorities below are recommended work order, not claims that every limitation is a defect.

Follow-up status: rank1 diagnostics are now implemented. The two original probes still omit
unsupported symbol/import content, but each now emits DOCX_CONTENT_OMITTED. Extraction/rendering
itself remains outside scope. Rank2 design is now documented in [the nested-table contract](nested-table-contract.md), with
owned HTML/DOCX default fixtures. Shared options/reservation accounting and chunk metadata safeguards are now implemented.
Descriptor validation and opt-in HTML preservation are now implemented. Markdown DOM delegation is also implemented. DOCX preservation is now implemented with bounded child snapshots and merge/source coordinates.
Unsupported formats/modes reject the option. Historical probe findings below
describe the audited baseline and have not been rewritten as if loss never existed.

TXT, HTML, Markdown, CSV, TSV, XML and DOCX have native implementations. PDF now has an initial native subset; see [PDF milestones](pdf-support.md). Common code, per-format dependencies, JSON, chunking and batch services are already separate Maven artifacts. The recent Word corrections are implemented and protected by saved-evidence checks in CI.

## Recommended implementation order

| Rank | Item | Classification | Evidence and impact | Next bounded deliverable |
| --- | --- | --- | --- | --- |
| 1 | DOCX unsupported-content diagnostics | Closed by diagnostic follow-up | Owned probes lose `w:sym` and internal `w:altChunk` content without a warning; surrounding text survives | Report these omissions explicitly, preserve safe source identifiers/offsets where supported, and add regressions without executing/importing external content |
| 2 | Nested HTML/DOCX table fidelity | Default flattening retained; bounded opt-in preservation implemented | Nested tables flatten to text with diagnostics in existing extractors; hierarchy and nested table structure are lost | Specify a nested-table output contract and test text/link/span preservation before changing representation |
| 3 | Broader real-document evidence | Validation gap | Native references span all seven formats, but most are synthetic; external DOCX examples cover few publishers/templates | Add a small permitted, diverse corpus with pinned inputs, extraction checks and explicit review status |
| 4 | Lower-memory document processing | Operational gap | File/byte loading, DOM/POI models, element lists and chunk staging still materialize; virtual-thread windows bound documents, not per-document heap | Profile representative large inputs, then choose one measured allocation/retention target while preserving immutable ownership |
| 5 | Token-budget chunking | Deferred feature requiring a design decision | ChunkOptions exposes code-point budgets only | Select a tokenizer/model contract and module boundary, then define hard-token/character/overlap/table semantics and reference cases |
| 6 | Further DOCX numbering coverage | Bounded supported subset / validation gap | Resolver supports common formats; unknown formats expose a marker and warning. Shared counters were established only for direct single-level decimal definitions | Add independently supported formats or definition cases individually; preserve diagnostics and avoid extrapolating shared-counter rules |
| 7 | Additional text-format options | Feature/compatibility backlog | CommonMark dialect, TXT heuristics and generic XML extraction deliberately differ from upstream | Choose one real consumer requirement or measured mismatch; do not enable every extension or copy NLP behavior without evidence |
| 8 | Additional formats | New modules, not unfinished existing partitioners | PPTX/XLSX/OCR remain absent; PDF has an native text/ruled-table-and-merge/image implementation | PDF is the current development priority; complete tables/images/OCR milestones before returning to DOCX; keep dependencies optional |

Rank1 was implemented as a bounded diagnostics change, with seven regressions. No font-rendering or HTML-import machinery was added. Rank2 now has a compatibility/output-contract proposal and owned baseline fixtures; implementation remains pending and requires no VM.

## Confirmed local omission probes

`benchmarks/tools/ProbeDocxOmissions.java` generates two owned inputs and records actual DocWeave text and warning codes:

| Input | Stored construct | Actual extracted text | Actual warnings |
| --- | --- | --- | --- |
| symbol.docx | Run containing `w:sym`, font Wingdings, char F0FC | Before; After | None |
| internal-altchunk.docx | Body `w:altChunk` referencing an internal text/html package part containing an import marker | Before; After | None |

The symbol probe establishes omitted source information, not a Unicode mapping for that font. The altChunk probe establishes that an internally related payload is not represented or reported; it does not establish what Word would render. XML walker inspection agrees: `DocxParagraphText.walk` has no symbol case, and `DocxBodyView.collect` accepts paragraphs/tables/selected wrappers but drops altChunk through its default branch. No production parsing behavior was changed during this audit.

Reproduce after building runtime artifacts/classpath as described in [saved Word validation](../benchmarks/word-style-validation.md):

```sh
DOCWEAVE_CP=$(cat docweave-all/target/runtime-classpath.txt)
java --class-path "$DOCWEAVE_CP" benchmarks/tools/ProbeDocxOmissions.java /tmp/docweave-omission-probes-001
```

The output directory must not already exist. Generated binaries/reports belong in a local scratch/run directory; they are not independent Word expectations. The audit run used `/tmp/docweave-omission-audit-135eqvib/probes`, which is ephemeral; the launcher is committed for reproduction.

Rank1 acceptance criteria:

1. Both constructs produce explicit `DOCX_CONTENT_OMITTED` diagnostics rather than disappearing silently. Explain unsupported import/symbol behavior without exposing payload text in diagnostic messages.
2. Surrounding text, order, IDs, links and code-point offsets remain correct. No invented glyph, guessed font mapping or rendered page metadata.
3. External relationships are never fetched. No field/import execution, font lookup, arbitrary embedded-file extraction or unbounded payload expansion.
4. Tests cover applicable body/table/supplemental contexts, selected revisions and repeated occurrences. Existing byte/archive/depth/element limits and interruption semantics remain intact.
5. Public APIs remain interface-based; implementation helpers remain hidden with documented responsibilities. Full Java/Javadoc verification, tooling tests and saved Word checks stay green.

## Validation gaps that are not missing implementations

- Word object-model collection completed: six original cases, ten controlled followups, four direct-heading controls. All16 comparator-supported cases match after fixes; the four heading controls have Java effective-policy regressions. Independent human review remains pending. CI does not run desktop Word.
- WholeTable numbering retains an existing local policy; saved evidence directly established only the outline correction. Other numbering formats, multilevel/link sharing, visual-only branch effects, last-edge symmetry and complex conflicting cached conditions have narrower evidence than their local policies.
- The upstream chunking suite has41 comparisons:32 exact normalized matches and9 documented differences. This is measured coverage, not complete upstream parity. Native synthetic references exist for all seven formats.
- Broad real-world layout, authoring-tool diversity, multilingual numbering, malformed package variations and complex revision/merge interactions need additional examples and review. No new Word run is currently planned for the recommended diagnostics step.

## Deliberate project boundaries

| Area | Current boundary or decision |
| --- | --- |
| DOCX rendering | Stored-part extraction; no reliable pagination/coordinates, visual reading order, OCR, arbitrary drawings, font-glyph rendering or field evaluation |
| HTML | Semantic DOM, no browser/CSS/JavaScript rendering or network asset fetch |
| XML | Generic ordered text, namespaces and attributes; no XPath selection/raw-tag parity, DTD/entity fetching, schema or stylesheet execution |
| Markdown | CommonMark plus optional GFM tables and documented raw-HTML policies; other extensions require explicit scope |
| JSON | Supported element taxonomy/top-level schema is strict; unknown metadata round-trips. Broader top-level schema needs a deliberate contract change |
| Chunking | Character budgets, literal separators and documented table/overlap differences; no tokenizer selected |
| Cancellation | Cooperative checkpoints and optional batch deadlines exist; direct synchronous APIs are untimed and indivisible parser/I/O calls may delay interruption |
| Packaging | Maven modules implemented; local beta.8 migrates packages to `io.docweave` with a breaking import change. JPMS remains deferred |
| Release | License choice, Sonatype namespace verification and release configuration pending. These affect publication, not local parsing; this audit is not a release decision |
| Expansion | PPTX/XLSX, advanced PDF tables/layout/OCR, legacy Office/email/EPUB/RTF, URL adapters and connectors are separate future capabilities |

## Deferrals already closed

Do not reopen these as missing work: multi-module conversion; interface-based partitioner discovery; DOCX notes/comments/textboxes/images/revision and cached-field policies; declared/default paragraph style semantics; stored-grid conditional regions; bounded saved-element reads; batch deadlines/incremental delivery; structural table splitting/header repetition/pass-through; selected allocation reductions; Word collection and the measured policy corrections; and private hosted CI with saved-evidence comparison.

Historical development records describe earlier states and are not the current backlog. The roadmap links this dated audit; README Word-collection status has been corrected. The rank1 diagnostics follow-up is complete. The nested-table contract review is complete. The shared policy/budget and chunk-provenance foundation is implemented. Descriptor capture/validation and HTML preservation are implemented. Markdown DOM delegation and DOCX preservation are implemented. Owned nested-table corpus and resource/offset boundary validation are now expanded. Preservation allocation/retention is now profiled in benchmarks/nested-table-memory.md. Absent/empty HTML span fast paths are implemented and remeasured. Inline Unicode append allocation is isolated and reduced with compatibility tests. Table HTML escaping allocation is now measured and reduced in valid-character appends. Next broaden independent DOCX corpus/fidelity coverage beyond the current template family, without a new format.
