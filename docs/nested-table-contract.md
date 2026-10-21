# Nested-table preservation contract

Status: shared policy/budget accounting, descriptor validation, chunk safeguards and **HTML, Markdown DOM and DOCX opt-in preservation are implemented**. Default extraction still flattens nested tables with a diagnostic. This contract is based on local owned fixtures, not a Word layout comparison or a claim of upstream parity.

## Observed behavior

The [owned probes](../benchmarks/review/nested-tables/README.md) contain two sibling child tables in one outer cell, a grandchild, repeated text, a link, Unicode and a child colspan. The HTML case also includes a child caption. These fixtures exercise equivalent structural questions, not byte-identical cross-format rendering.

| Property | HTML baseline | DOCX baseline |
| --- | --- | --- |
| Top-level output | One outer Table | One outer Table |
| Nested text | Included once in containing-cell text | Included once in containing-cell text |
| Nested link | Preserved at code-point offset37 | Preserved at code-point offset24 |
| Child hierarchy and child grid in text_as_html | Lost | Lost |
| Child colspan in outer text_as_html | Lost | Lost |
| Flattening warning count | One for the outer table | Three, one per flattened child/grandchild |
| Separators | Spaces inside flattened cell, tabs between outer cells | Paragraph/child-row newlines and tabs, then tabs between outer cells |

HTML's HtmlDomWalker excludes descendant table rows from the outer grid, but renders their text into the containing cell. DOCX's DocxTableExtractor recursively extracts children and composes text/link offsets, but discards each child's rendered table metadata. Existing merge, style, numbering and source-part behavior must continue to work.

The new NestedTableBaselineTest checks exact default elements/IDs/metadata/warning codes against the pinned snapshots and validates link offsets. It is a compatibility guard, not a new quality expectation. The original inputs and baseline hashes are recorded in the manifest.

## Representation policy

FLATTEN remains the default. The explicit PRESERVE_METADATA mode retains child table snapshots in metadata on the existing root Table. Root text, text_as_html, IDs, order and current warning policy remain identical to FLATTEN. Preserving metadata does not mean the flat text or outer HTML has become structurally nested, so NESTED_TABLE_FLATTENED remains truthful.

Do not emit extra top-level Table elements in the first implementation: that would duplicate text for existing consumers, change IDs/sequences, complicate hierarchy and introduce a new chunking contract. Do not insert nested table markup into the canonical outer HTML, whose splitter currently consumes a flat grid.

Implemented foundation: a JDK-only immutable NestedTableOptions value with Mode.FLATTEN / PRESERVE_METADATA and explicit metadata limits, selected through the existing PartitionOptions builder interface. Preserve compatibility with custom option/builder implementations through default behavior or explicit unsupported-mode failure. Concrete helpers stay hidden; no new public parser classes.

The option works for HTML, DOCX and the HTML extraction path used by Markdown EXTRACT_DOM. It does not claim nested-table structure for CSV/TSV, CommonMark AST literal HTML, generic XML or arbitrary saved-element JSON. Disabled preservation allocates no descriptor tree. Partitioner capability defaults to false; HTML, DOCX and Markdown advertise support; Markdown checks its selected mode/provider before parsing. The other built-ins still reject PRESERVE_METADATA through UNSUPPORTED_OPTION at facade and direct SPI entry.

## Metadata schema (version 1)

Use nested_tables_version=1 and a flat nested_tables list on each root Table. Descriptors appear in depth-first source order and contain:

| Field | Meaning |
| --- | --- |
| index | Zero-based identity within this root Table; never an element_id |
| parent_index | Immediate parent descriptor index, or omitted for a child directly in the root |
| path | Root-to-child steps with source logical row, column and table_index (sibling table ordinal within its containing cell) |
| start_index / end_index | Half-open code-point interval in the **root flattened Table.text**, including for grandchildren |
| text | Child's own normalized table text, which may use different separators from its root interval |
| text_as_html | Child's flat canonical HTML, retaining its own grid/spans; grandchildren remain represented through their own descriptors |
| links | Child-local links whose offsets refer to this descriptor's text, not root text |
| captions | Ordered child caption text when available; not additional root caption elements |
| source_metadata | DOCX-only whitelisted child semantic annotations using child-local offsets; no recursively embedded nested_tables |

HTML path row/column identifies the source logical cell origin, accounting for colspan and occupied rowspan positions. Rows include empty source rows; sibling ordinals count direct tables in their containing cell. For DOCX, paired owner_row/owner_column identify content appended to a different merged origin and paths include grid gaps. Owner coordinates occur only when different from the source origin. table_index counts direct child tables even when empty, so identical or empty siblings remain distinct. Parent links must point backward to an earlier descriptor. No child ID should masquerade as a global Element ID or parent_id.

Root intervals locate the embedded flattened contribution; they do not imply descriptor.text equals the root substring. HTML flattening and child table normalization have different separators. Generate intervals during composition, not by searching for child text: repeated labels make substring matching ambiguous. Preserve a zero-width interval for an empty child where the outer Table exists. An empty child before its parent's first visible text is placed at that parent's start, after any excluded normalization separator. If the current policy omits an entirely empty root, do not invent a new root merely to attach metadata in the first version; document that limit.

Captions, links and semantic annotations must be local to their declared text coordinate space. Do not copy whole-table range offsets into fragments or confuse source logical cells with rendered pixel coordinates. External assets/relationships remain unfetched.

## Implementation responsibilities

1. Shared core values define mode/budgets; a hidden document-local collector accounts for descriptor counts, depth and metadata payload. No jsoup or POI types in core.
2. The HTML adapter discovers child ownership and captures the root-rendering contribution ranges while rendering the containing cell. The child grid is built separately using existing limits/canonicalization, without emitting extra elements or fetching links.
3. The DOCX adapter retains metadata already produced by recursive extraction. Its separate preservation sidecars shift root-relative descriptor ranges along with content through paragraphs and merged cells. Child-local annotations remain unshifted within snapshots. Resolved numbering/style state is reused; list counters are never consumed a second time.
4. A shared descriptor writer validates parent/path/interval invariants and emits only JSON-compatible immutable snapshots. Keep rendering, geometry, budget accounting and emission separate.
5. Use streams for clear transformations, but retain explicit traversal where order, interruption or counter state matters. Follow the project Java style and document public and internal contracts with Javadocs.

## Resource and chunking contract

Add positive maxNestedTables, maxNestedDepth and maxMetadataCodePoints limits for PRESERVE_METADATA. Current conservative initial defaults are256 children, depth16 and1Mi code points of cumulative descriptor payload. Boundary/overflow/interruption accounting is tested; adapter stress tuning remains future work. These are additional metadata budgets, not replacements for existing source/archive/XML/DOM/table/cell limits and not heap guarantees.

Account for repeated copies (child text, HTML, captions, links and semantic strings), paths and all grandchildren. A flat descriptor list avoids recursive metadata depth, but does not avoid quadratic text duplication through ancestors. The shared writer counts every stored descriptor string key/value by Unicode code point and one unit per scalar/container. Repeated snapshots and paths are charged again; the fixed root version/list envelope is excluded. This is a defined payload measure, not serialized JSON byte size. HTML preflights count/depth before allocating child range maps, bounds each child text/HTML/link/caption payload before appending, then validates and charges the combined descriptor before immutable copying. Poll long traversals. Fail TABLE_LIMIT when preservation cannot satisfy a limit; do not silently fall back while claiming preservation. Existing fixed16MiB decoded provenance limits still apply independently.

Generated TableChunks and mixed CompositeElements must drop nested_tables/version from their direct metadata: their text/ranges are rewritten. When provenance is enabled, preserve the full source descriptor list only inside original elements. Exact skipTableChunking pass-through retains metadata untouched. Even a fitting canonicalized table can have rewritten whitespace, so keeping source offsets there is unsafe without explicit remapping. First-version preservation does not add fragment-local nested-table remapping.

The chunker recognizes these metadata keys so arbitrary table metadata retention cannot carry stale ranges. JSON round trips must retain descriptor values and immutability; metadata schema extension must not weaken the strict top-level element schema.

## Delivery sequence and acceptance

1. Shared options/reservation accounting, capability gates, descriptor validation/actual payload measurement and chunk metadata/provenance rules are implemented with backward-compatibility guards. Unsupported adapters fail explicitly.
2. HTML preservation and Markdown DOM delegation are implemented with sibling/grandchild/empty/repeated-label/link/caption/span fixtures, explicit AST-mode rejection and standalone missing-provider checks.
3. DOCX preservation is implemented with merged origins, grid gaps, lists, revisions, supplementary parts and text-box anchors. Counter/link/default-output regressions pass.
4. Cross-format JSON/chunking/limit/cancellation checks and support documentation are implemented. Further corpus diversity, resource stress profiling and independent review remain validation work.

At every stage, default fixtures must match exact semantic element output (including IDs/metadata) and current warnings, while opt-in tests validate descriptor grids, ordering, paths, parent indices and both coordinate spaces. Stress tests must fail predictably at each configured boundary without mutating shared state. Saved Word comparisons must remain unchanged. No VM is required for this stored-structure implementation; independent rendering review remains separate.

## HTML implementation boundaries

HTML uses the normal parsed/filtered DOM and existing input/depth policy. Preservation additionally enforces TableLimits.defaults() on source tables/cells; logical cell origins and spans used for preservation must also fit those row/column/cell limits. No expanded grid is allocated. Oversized geometry fails TABLE_LIMIT, overlapping spans fail MALFORMED_DOCUMENT, and children without a supported containing cell fail MALFORMED_DOCUMENT. Invalid/nonpositive integer spans retain the existing ignored-geometry behavior; browser layout and rowspan=0 semantics are not simulated. Default FLATTEN is unchanged by these additional opt-in checks.

Captions are child-local text only, and child HTML is a flat table of owned rows/cells with escaped normalized content and valid source span attributes. It contains no nested markup, links or fetched assets. Grandchildren have separate descriptors. Root ranges are captured during normalization of the original containing cells; snapshots use child-local row/cell separators. Source logical coordinates describe the DOM after normal parser repair and filtering. Entirely empty roots remain omitted and have no descriptors.

Helpers separate source ownership/geometry, inline normalization/range capture, bounded HTML serialization, descriptor validation and document-local budget accounting. No additional public implementation classes or parser dependencies are exposed. Tests cover exact default output, hierarchy/coordinates, local links/captions, escaping/Unicode, zero-width ranges, budget boundaries/document reuse/concurrency, JSON and chunk provenance. The Windows VM is unnecessary for this stored HTML structure work.

## Markdown DOM delegation

Select both PRESERVE_METADATA and MarkdownOptions.RawHtml.EXTRACT_DOM. The Markdown strategy renders CommonMark plus raw HTML and delegates the combined DOM, original context/preservation policy and custom TableLimits to the same first discovered HtmlExtraction service as default DOM extraction. There is no additional parser dependency on HTML; applications install docweave-html explicitly or use the all artifact.

HtmlExtraction exposes a backward-compatible default-false preservation capability. The built-in HTML bridge advertises true. Missing services fail UNSUPPORTED_FORMAT; incapable first services or AST modes PRESERVE_TEXT/SKIP fail UNSUPPORTED_OPTION. Preservation checks occur before Markdown parsing/rendering/emission, while default FLATTEN retains its existing parse/depth/service error precedence. A later capable provider is not silently substituted for an incapable first service. Custom providers remain responsible for their advertised contract.

CommonMark AST depth, HTML DOM depth/filtering/header/footer policy, custom per-table limits and document-wide descriptor budgets all remain active. Paths/intervals describe the rendered/repaired HTML DOM, not Markdown source positions. Pipe tables without nested HTML acquire no descriptors; fenced HTML stays literal code. Child links resolve against the configured base URI. Metadata, warnings, immutable JSON snapshots and chunk provenance follow the HTML contract. DOCX uses the same descriptor contract; no Word VM is needed for DOM delegation.

## DOCX implementation boundaries

DOCX captures each selected child during its existing single recursive extraction. Child text, flat HTML/spans, links, captions and semantic annotations are retained without rerendering paragraphs or replaying numbering. The internal text accumulator carries preservation sidecars separately from public annotations; child ranges shift through paragraph separators, tabs, grid-span placeholders and append-to-origin merges. Source-preorder tickets restore descriptor order even when vertical merges move a later source child's text before an earlier sibling. Paths identify source logical cells after revision selection, including grid gaps; paired owner coordinates identify merged render origins. Grandchild paths prefix the parent's complete source/owner step. Child intervals remain root-relative, while snapshots/semantic ranges remain child-local. Empty children get zero-width ranges and distinct ordinals. DOCX retains its existing policy for empty root Table emission.

One document-local budget spans body, enabled headers/footers, notes/comments and recursively emitted text boxes. Count/depth are checked before child descent. Before retaining each snapshot, an incremental lower-bound payload check includes text/HTML/links/captions/semantic copies; final descriptor validation charges the complete payload including paths, indices, root ranges and owner coordinates before immutable copying. Native DOCX render strings/models still materialize under existing archive/XML/table/cell limits; this additional retention accounting is not bounded-heap or streaming extraction. Failure aborts the document; a new invocation gets independent state.

The DOCX source_metadata whitelist is docx_list_items, docx_table_style_paragraphs, docx_references, docx_revisions, docx_field_instructions/policy, docx_merge_content_policy, docx_table_style/source/unresolved/conditions_omitted, and docx_supplement_anchors. Range lists validate child-local start/end bounds; reference and anchor points may be zero-width. Anchors contain kind/start_index only, without XML nodes, byte payloads or extra emissions. Field instructions are stored cached-result metadata and never executed. Nested table keys are forbidden inside snapshots; supported JSON-compatible scalar/container values are deeply frozen and measured. Stored child w:tblCaption/@w:val values populate captions without extra top-level caption elements. No visual layout, inferred pixel geometry, glyph resolution or external asset retrieval is added.

Owned tests cover exact default/root compatibility, child spans/links/hierarchy, merge relocation and preorder, gap/owner coordinates, empty children, list counters, independent child styles, accepted/original/all revisions, references/fields/captions/text-box anchors, enabled/disabled supplemental budgets, exact payload/count/depth boundaries, native table limits, JSON/chunk provenance, interruption and concurrent virtual-thread reuse. DOCX-only module tests keep HTML/Markdown/JSON dependencies absent. Broader real-world corpus and independent Word rendering review remain separate; no VM is needed for this stored-structure feature.

## Cross-format validation

The [owned model corpus and boundary suite](../benchmarks/nested-table-validation.md) now checks72
HTML/Markdown DOM/DOCX cases against independently constructed hierarchy/path/grid/token expectations,
exact default count/depth boundaries, cumulative payload boundaries and a larger escaped-Unicode
workload. Combined DOCX horizontal/vertical merges retain grandchild path prefixes and supplemental
anchor coordinates. Defaults, warning equality and JSON round trips remain guarded. This broadens
structural regression coverage; real-world corpus diversity and rendering/heap evidence remain separate.

The [memory profile](../benchmarks/nested-table-memory.md) now records flattening/preservation allocation,
process live-heap observations and sampled stacks for pinned wide/deep inputs. Payload units remain
separate from heap bytes. Absent/empty HTML spans now bypass numeric parsing with unchanged nonempty-value semantics;
fresh measurements record lower preservation allocation. Representation/budget/ownership are unchanged.
