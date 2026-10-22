# Compatibility specification — draft 0.2

## Reference

Upstream: https://github.com/Unstructured-IO/unstructured

Pinned commit: `347fbbfd3e84e228017f125e5c5dbdc326ef7de2` (resolved 2026-10-14).

Baseline runs must record the commit, Python version, dependency lock, OS, OCR language packs, model identifiers and hashes, and partition options. The commit alone does not guarantee identical OCR or inference results. Verify source behavior at this commit before finalizing expected outputs; earlier architectural review used moving `main` URLs.

## Scope

The first release targets the OSS partitioning and chunking workflow. Hosted commercial APIs, model training, ingestion orchestration, embeddings, audio, and connector coverage are deferred. Native OCR and ONNX inference may be optional modules; a pure-Java runtime is not currently a requirement.

## Contract

The compatibility JSON representation is an ordered array of objects with `type`, `element_id`, `text`, and `metadata`. Preserve upstream spelling and omit absent metadata rather than synthesizing values. Unknown metadata must survive a JSON round trip. Some future non-text element types may require additional top-level fields; do not claim complete schema coverage yet.

Initial types: Title, NarrativeText, UncategorizedText, ListItem, Table, Image, Header, Footer, FigureCaption, PageBreak, CompositeElement, TableChunk.

CodeSnippet is also supported so chunking can preserve its whitespace. Unknown element types and additional top-level fields currently fail explicitly; unknown metadata is preserved by the JSON codec. Metadata snapshots are deeply immutable.

Initial metadata: filename, filetype, page_number, page_name, parent_id, category_depth, coordinates, languages, links, text_as_html, data_source, is_continuation, orig_elements.

Implemented provenance: `orig_elements` is a base64 string containing zlib-compressed JSON. Upstream names this gzip but uses zlib. DocWeave encodes/decodes the same container, removes recursively nested provenance when rechunking, and rounds original coordinates/probabilities as upstream does. JSON key ordering, whitespace, escaping, and resulting compression bytes are not byte-for-byte identical. The decoder caps decompressed data at 16 MiB. Encoding also rejects serialized originals above that limit so generated provenance remains decodable.
The decoder validates base64 in bounded aligned blocks before streaming inflation, retaining
strict encoded-tail rejection and the historical UTF-8/zlib-member behavior. It avoids whole
encoded/compressed byte-array copies; decompressed JSON and final elements still materialize.
See [owned compatibility and scoped allocation evidence](../benchmarks/review/originals-bounded-decode/README.md).


Unreleased split-source safeguard: a single source over 1,000,000 combined text, raw HTML and
image-base64 Unicode code points omits repeated `orig_elements` only when it actually produces
multiple chunks. Every affected chunk records `orig_elements_omitted=oversized_split_source`
and `orig_elements_source_characters`. Such chunks cannot restore the full original from
embedded provenance; retain the original partition result where reconstruction is required.
Text/HTML content, ownership and chunk boundaries stay unchanged. Unsplit large sources,
exact-threshold sources, multi-source groups and explicit table pass-through keep their contracts.
See [the measured output reduction](../benchmarks/review/chunk-split-source-provenance/README.md).
This source-informed safeguard tracks newer upstream main; it does not relabel saved goldens.


Coordinates must declare their system and dimensions. Internal coordinates should use a documented top-left origin with explicit units; exporters must convert to upstream systems. Missing pagination stays missing, particularly in DOCX.

Stable-ID compatibility must match upstream input formatting and hashing, including absent values and page sequence counters. Document identity and parser output identity are separate concepts. Do not promise ID equality if element segmentation differs.

## Feature matrix

This matrix records planning targets, not completed capabilities. The current seven-format support
list is defined by DocumentFormat; PDF has an initial native subset described in [PDF support](pdf-support.md); OCR/advanced strategies below remain requirements.

| Capability | Priority | Compatibility target |
|---|---|---|
| Element JSON and metadata | P0 | Exact schema and round trips |
| Basic / by-title chunking | P0 | Exact on identical supplied elements |
| TXT, HTML, Markdown, CSV/TSV, XML | P0 | Options plus measured extraction quality |
| DOCX, PPTX, XLSX | P1 | Structure, metadata, ordered content |
| PDF fast | P1 / initial native subset | Positioned quarter-turn text/opt-in paragraph and two-column layout, ruled/merged and opt-in aligned numeric tables, raster placements; reading order not validated |
| PDF/image OCR | P1 | Text, page coordinates, configured languages |
| PDF/image hi_res | P2 | Layout classification, merging, tables |
| Legacy Office, email, EPUB, RTF | P2 | Subsequent format adapters |
| Token-based chunking and advanced table chunking | P2 | Exact after tokenizer/model choices are pinned |

## Options and errors

Provide typed format-specific options. Inventory each pinned upstream option and classify it as supported, deferred, or intentionally different. Unsupported options must produce an explicit error. Strategy availability is queryable before processing.

For future PDF/OCR modules, implement fast, ocr_only, hi_res, and auto. Reject fast for images. An explicitly requested unavailable strategy fails by default; optional fallback must be enabled and reported. This default is an intentional divergence from upstream fallback behavior. Auto may select a strategy per page for mixed PDFs; record this divergence and the strategy used for every page.

Chunking must specify hard/soft limits, separators, oversized-element splitting, overlap and overlap_all, table isolation/splitting, page boundaries, title boundaries, small-section combining, and metadata consolidation. Match Python Unicode code-point counts rather than Java UTF-16 code-unit counts. Use pinned source/tests to resolve documentation discrepancies.

## Implemented chunking options

| Upstream option | Java option | Status |
|---|---|---|
| max_characters | maxCharacters | Implemented, default 500 |
| new_after_n_chars | newAfterCharacters | Implemented, default/clamped to hard maximum |
| overlap / overlap_all | overlap / overlapAll | Implemented, default 0 / false |
| multipage_sections | multipageSections | Implemented for by-title, default true |
| combine_text_under_n_chars | combineTextUnderCharacters | Implemented for by-title, default hard maximum |
| include_orig_elements | includeOriginalElements | Default true; oversized actually split singleton sources omit repeated provenance with explicit metadata |
| isolate_table | isolateTable | Default true; false permits text/table composites, with conservative overlap/metadata differences |
| max_tokens / tokenizer | — | Deferred |
| text_splitting_separators | textSplittingSeparators | Ordered literals; default newline/space then code-point fallback; visible delimiters retained |
| repeat_table_headers | repeatTableHeaders | Implemented, default true; strict budget/alignment-aware repetition |
| skip_table_chunking | skipTableChunking | Implemented, default false; exact isolated table pass-through |

Tables and TableChunks are isolated from narrative overlap. Text-only oversized tables emit TableChunks with table IDs, chunk indexes, continuation flags and carried-header count zero. HTML table compaction and structural row/cell/text splitting are implemented with strict text and HTML budgets. Windows below 50 code points use text-only fallback when compact HTML does not fit. Default title combining can merge adjacent small sections, including sections separated during staging by page boundaries; set the combining threshold to zero to preserve those boundaries.

Composite metadata uses upstream first-value, list-concatenation, list-union and enrichment-origin merging rules. Coordinates, hierarchy and other unsuitable fields are dropped from chunk metadata. Unknown extension metadata is dropped from composites, retained on tables, and remains available in original elements when enabled.

With `isolateTable(false)`, table-family elements can share staged/composed chunks with neighboring
text or tables when ordinary text limits permit. Mixed output is CompositeElement, based on
normalized stored table text; table-only HTML/cell geometry/extraction-method metadata is omitted
from the composite and retained in source provenance when enabled. Disabling provenance omits
that structure from mixed output. Single-table groups still follow structural table processing.
`skipTableChunking(true)` with isolation disabled is rejected. Heading/page staging, small-section
combining and character limits continue to apply. Incoming and outgoing overlap is blocked for
all table-bearing groups, including groups created during combining. Unlike pinned upstream's
opt-out path, Java conservatively retains this overlap barrier and omits table-only HTML from
mixed metadata. Regression tests validate the local contract, not full opt-out parity.

Custom separators apply only to oversized narrative/code and text-only table splitting, including
small-window HTML fallback. They do not alter normal element joining, prose normalization or
structural row/cell splitting. Lists are immutable snapshots; an empty list selects code-point
fallback, and empty/null/malformed Unicode entries are rejected. Each separator is tried in order,
using its last eligible occurrence within the hard budget and beyond the overlap region.
Whitespace-only delimiters are consumed; non-whitespace delimiters remain on the left fragment,
with trailing whitespace trimmed. Existing overlap and continuation/provenance policies apply.
This intentionally differs from the pinned Python splitter's regex compilation and unconditional
separator removal: Java uses literals and retains visible text. Default golden cases still pass;
custom-separator upstream parity is not claimed.

## Native TXT/HTML partitioning

Public services/builders now expose interfaces and static factories with hidden implementations. Native partitioning accepts local paths, defensive byte snapshots and decoded text. Explicit TXT/HTML routing is available; otherwise HTML content signatures precede supported filename hints. Recognized binary signatures and unsupported known extensions fail explicitly. This detector is deliberately narrower than upstream libmagic/Tika-style broad detection.

HTML and TXT strategies implement `Partitioner`, selected at runtime through an injected `PartitionerRegistry`. Built registry snapshots are immutable. Custom strategies receive a `PartitionContext` preserving IDs/source metadata, emission limits and warning collection. They must support concurrent calls without retaining context state. A registry can replace supported-format strategies; unsupported formats still require detector/model support.

TXT defaults to blank-line paragraph grouping, joining wrapped lines. Bullet/numbered items remain separate; indented continuation lines stay with their list item. Optional line mode and classification disabling are supported. Category heuristics use list markers, text length and sentence punctuation. They intentionally differ from upstream language/NLP classifiers. Opt-in `ParagraphMode.AUTO` separates short title-case/all-capital lines (up to eight words/80 code points) and completed sentences followed by an unindented uppercase/title-case or uncased-letter line, while joining soft wraps and indented list continuations. These deterministic rules can misread abbreviations or title-like prose and are not upstream automatic-grouping parity. `TxtOptions(minPartition, maxPartition)` applies after grouping and optional bullet removal: a hard code-point maximum prefers whitespace, then code-point boundaries; categories stay those of the source paragraph. A soft minimum merges a pending short prose fragment with an entire next prose paragraph of the same output category only when the result fits the maximum. It can cross blank/line boundaries; titles/lists are barriers even without classification. Short remainders are never discarded. Defaults disable merging and practical splitting. Surrogate pairs are preserved; grapheme clusters may split.

HTML emits heading, paragraph, list, table, header/footer, caption and preformatted-code elements in DOM order. Wrapper elements do not duplicate descendant text. Heading levels and nested lists supply hierarchy; links retain normalized text, resolved URLs when a base URI is provided, and Unicode code-point offsets. Tables use tab-separated cells/newline-separated rows plus compact text/cell-span HTML. Nested tables flatten into cell text and report a warning. Source HTML is parsed locally without JS/CSS rendering or asset fetching.

Unknown elements and legacy font wrappers are transparent: inline text retains adjacency and
block children are exposed to the semantic walker. Original depth limits are checked before
unwrapping, and explicitly hidden subtrees are removed first. A reviewed non-content policy
excludes audio/canvas/datalist/dialog/head/iframe/ix:header/noembed/noframes/object/select/svg/
textarea/title/video/xml subtrees, retaining surrounding text. Literal ix:header is recognized;
arbitrary namespace-prefix equivalence is not inferred. The rule also applies to Markdown
EXTRACT_DOM. It does not implement CSS display, embedded-resource loading, MathML/KaTeX semantics
or source page-number propagation. General known-inline-container/block segmentation remains a gap.

Root table captions remain separate FigureCaption elements; their text is omitted from the root
Table's text/text_as_html. Caption-only tables can therefore emit a caption without a Table.
This intentionally differs from unreleased upstream main, which includes captions in Table
text/HTML and suppresses caption-only tables. jsoup can repair invalid head children into body
content differently from lxml. See the [scoped upstream review](../benchmarks/review/upstream-html-20261016/README.md);
released references remain pinned and unchanged.


IDs follow the pinned filename/text/page/sequence hash shape; page is absent. No coordinates, languages, timestamps or page numbers are fabricated. Equal IDs against upstream require equal element text/segmentation and filename. Classification, segmentation, table HTML, DOM repair, boilerplate policies and metadata therefore require measured comparisons, not an assertion of extraction parity.

Byte sources decode with explicit charset, BOM, HTML head meta charset, then UTF-8. Conflicting BOM/charset or malformed data fails; decoded Text sources use their UTF-8 footprint and ignore byte-encoding options. Default limits are 10 MiB input, 100,000 elements and depth 256. Checkpoints propagate interruption. These limits do not impose an execution deadline.

Current parser tests cover synthetic semantic/encoding/resource cases and raw-file batches. Native HTML references now cover one owned structured input; Markdown adds structured and mixed-HTML inputs. See [markup comparisons](markup-comparison.md). Eight native TXT/CSV/TSV reference runs now cover baseline prose, grouping/size keywords and header policies with explicit differences; see [text comparisons](text-comparison.md). Separate DOCX references and chunker goldens retain their documented scopes.

## Validation evidence

Native Markdown uses CommonMark Java 0.30.1 with optional GFM tables and an AST walker. Headings/lists supply hierarchy; prose strips emphasis markup and resolves links/reference definitions; code preserves CommonMark literals. Standalone images retain URL/alt text. Raw HTML is preserved as literal text with one diagnostic by default, or explicitly skipped. AST depth and table budgets are configurable. Opt-in `RawHtml.EXTRACT_DOM` renders the complete parsed document, then uses the shared HTML DOM walker and the original Markdown emission context. Markdown/HTML headings and lists share hierarchy, links retain code-point offsets, invisible/script content is removed, and HTML depth/header/footer policies apply. Raw/pipe tables retain canonical HTML and links; per-table budgets also apply to nested source tables before flattening, counting source cells rather than span-expanded positions. DOM mode follows HTML prose classification and image-alt-text behavior, omitting AST-specific image references, code-info and pipe-table alignment metadata. The default AST mode remains unchanged. Neither mode establishes upstream parser parity.

CSV/TSV use Apache Commons CSV 1.14.1 quoted-record parsing. Each file emits one Table, with tab/newline display text, escaped cell HTML and extension row/column/header counts. Values remain strings; no numeric/header inference is performed. First-row headers are the default. Blank physical lines are ignored; quoted empty cells and multiline fields are preserved. Inconsistent widths fail by default; permissive mode retains ragged rows and emits one diagnostic. Malformed quoted records and exceeded table budgets return typed failures.

Structured extensions `.md`/`.markdown`/`.csv`/`.tsv` determine format before HTML sniffing, since markup may be valid cell/Markdown content. Anonymous inputs require explicit format for these ambiguous syntaxes. GFM cell padding/truncation follows the pinned CommonMark library. The extension inventory is CommonMark core plus optional GFM pipe tables. Strikethrough, task-list semantics, bare-URL autolinking, footnotes, definition lists, math and front matter have no dedicated extension support; input follows CommonMark parsing rather than promising those semantics. Markdown inside raw HTML blocks follows CommonMark boundaries, so embedded asterisks may remain literal. HTML-backed Markdown/delimited tables now use shared structural chunking.

Format-specific native regression tests and mixed-file batches cover these policies. Existing upstream-generated goldens still validate element-only chunking; Markdown now has two native reference inputs with explicit dialect/DOM differences; delimited references now cover aligned header policies, numeric/control-character behavior and MIME differences; see [text comparisons](text-comparison.md). See [markup comparisons](markup-comparison.md).

`docweave-integration-tests/src/test/resources/chunking-goldens.json` contains executable outputs from the pinned Python basic/by-title chunkers on synthetic cases. It records source hashes and dependency versions. Generated UUIDs are normalized and compressed provenance is compared after decoding; extraction or byte-exact serialization parity is not established by these tests.

All three current chunking reference files were generated on Python 3.13.16, within upstream's declared range (>=3.11,<3.14), using pinned dependencies required by these code paths. Five selected upstream implementation hashes are verified before import; unsupported Python minors are rejected. The 17 baseline and eight table normalized outputs are unchanged from the earlier Python 3.14 run. Java tests execute without Python or the upstream checkout. Selected-file verification does not establish integrity of the entire source tree or extraction parity.

`docweave-integration-tests/src/test/resources/chunk-options-goldens.json` adds 16 real comparisons: 11 exact normalized
matches for whitespace/fallback separators and basic/by-title table isolation, plus five explicit
Java policy differences. Both `expected` (upstream) and separately reviewed `java_expected`
outputs are retained for visible delimiters, literal/regex semantics, mixed-table HTML metadata,
and two overlap-barrier cases. At the pinned commit, public chunking functions omit the custom
separator keyword, so those six cases use the real internal options/chunking path and record its
entrypoint. Isolation cases use public functions. No upstream code is patched or mocked during
generation. Across all three files, 32 cases compare exact normalized supported output and nine
assert documented differences; these counts do not establish full option or parser parity.

## Acceptance

1. Serialization round trips preserve supported fields and extension metadata.
2. Chunking matches reviewed golden cases with identical input elements.
3. Parser comparisons report text fidelity, category sequence, and metadata differences separately.
4. A reviewed corpus measures segmentation, reading order, and table accuracy; upstream output alone is not ground truth.
5. Set extraction-quality and latency thresholds after measuring the baseline; none are claimed today.
6. Processing limits cover bytes, pages, decoded images, archive expansion, recursion, time, and cancellation. URL loading is an optional adapter with bounded redirects and network policy.

## Sources

- https://github.com/Unstructured-IO/unstructured/blob/347fbbfd3e84e228017f125e5c5dbdc326ef7de2/unstructured/documents/elements.py
- https://github.com/Unstructured-IO/unstructured/blob/347fbbfd3e84e228017f125e5c5dbdc326ef7de2/unstructured/partition/auto.py
- https://github.com/Unstructured-IO/unstructured/blob/347fbbfd3e84e228017f125e5c5dbdc326ef7de2/unstructured/partition/strategies.py
- https://github.com/Unstructured-IO/unstructured/blob/347fbbfd3e84e228017f125e5c5dbdc326ef7de2/unstructured/chunking/base.py

## Native XML extraction

XML uses the JDK built-in StAX reader (Java 21 minimum) per document, with DTD processing and external entities disabled, empty external-DTD access and a rejecting resolver. DTD/custom-entity events fail as `UNSAFE_XML`. Malformed syntax returns a sanitized `MALFORMED_DOCUMENT` diagnostic without source text. No schema/stylesheet validation or XInclude expansion is performed.

Generic extraction emits direct text runs separated by child-element boundaries, preserving source order without duplicating descendant text. Each nonblank run is UncategorizedText with namespace-expanded `xml_tag`, a root-to-owner list `xml_path`, depth and optional `xml_attributes`. Namespace declarations are not ordinary attributes; sibling indexes and parent IDs are not synthesized. This is a documented generic strategy, not an upstream XML segmentation parity claim.

Whitespace is normalized unless preservation is configured or inherited through `xml:space`. Comments/processing instructions are ignored without splitting adjacent text; CDATA and predefined/numeric entities join adjacent text. Root-inclusive depth defaults to 256 and element-node count to 100,000, including empty nodes. Element/input limits and interruption checks remain centralized. These budgets are not heap limits or deadlines.

XML filenames are authoritative before HTML sniffing; anonymous inputs with an XML declaration route to XML, while declaration-free inputs require explicit routing to guarantee XML. XML declaration encodings and UTF signatures supplement existing strict byte decoding. Explicit charset/BOM policy takes precedence, and decoded Text sources ignore declaration encoding. Tests cover mixed content, namespaces, encodings, malicious DTDs, resource limits and concurrent use; five native upstream XML runs now retain explicit mixed-content ordering, classification, metadata, option and entity-policy differences; see [XML comparisons](xml-comparison.md). Full XML parity remains unverified.

Security settings follow the [Java 21 XMLInputFactory contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.xml/javax/xml/stream/XMLInputFactory.html).

## Native DOCX foundation

Apache POI 5.5.1 XWPF supplies stored body order, after bounded ZIP/XML preflight. Source files and byte snapshots are accepted; Text sources cannot represent DOCX. Anonymous ZIP inputs are attempted as DOCX, requiring `[Content_Types].xml`, `word/document.xml` and the non-macro Word main content type. Legacy/encrypted/macro-enabled Word and other ZIP formats are unsupported. Diagnostics report binary encoding; filename/text/sequence IDs remain centralized.

Titles use explicit/inherited outline levels or conventional heading/title styles; ListItems use direct/inherited numbering properties and nested list anchors. Other paragraphs are NarrativeText, with no NLP classification. Common numbering labels/counters are retained as metadata; pagination, coordinates and visual order are not inferred. Hyperlinks retain normalized displayed text and code-point offsets; block/row/cell controls retain structure. Notes/comments, text boxes, image references/optional bytes, revision selection and cached field results are implemented below; full Word rendering/evaluation is not claimed.

Tables preserve strings, row order, empty cells, cell paragraph breaks, repeated headers and grid-span/empty-vertical-continuation HTML spans. Plain text inserts logical empty positions; HTML is authoritative. Nested tables flatten with a diagnostic and retain links/references/anchors. Structured cells and omitted grid positions are preserved. Legacy horizontal and nonempty vertical merge content appends at the merged origin, with correct composed offsets. Unmatched continuations fail explicitly. HTML-backed DOCX tables now use shared structural chunking. Stored headers/footers are optional and emitted once around body content, not repeated per rendered page.

Resource defaults are 2,000 entries, 64 MiB expansion, 16 MiB per entry, XML depth 256 and 1,000,000 cumulative XML element starts. All declared XML parts (including non-XML extensions) are checked for DTD/custom entities and budgets before model construction. Preflight is in-memory and does not write archive entries or change global POI ZIP protections. Input/emission limits and cooperative interruption remain enforced. No execution deadline or heap bound is claimed.

Generated POI regression documents and an independently constructed OOXML fixture cover these policies. JSON/provenance round trips and concurrent reuse are tested. Two native pinned-upstream DOCX comparisons now retain full reference outputs and explicitly test shared behavior and content/classification differences. See [reviewed comparison](docx-comparison.md). Two published NRS reports now have hash-pinned external corpus diagnostics under default/stored-part policies; binaries remain fetch-only. Both share a template family and independent human review remains pending. See [external corpus findings](../benchmarks/corpus/docx/README.md). Broader DOCX parity is unverified.

References: [POI 5.5.1 release](https://poi.apache.org/download.cgi), [IBody source-order contract](https://poi.apache.org/apidocs/dev/org/apache/poi/xwpf/usermodel/IBody.html). Implementation signatures are checked against the pinned 5.5.1 artifacts.

## Structural-table chunking evidence and differences

A hidden immutable cell-ownership model, fragment serializer and per-call splitter compose structural chunking behind the public Chunker interface. Complete rows/span groups precede cell/text fallbacks. HTML cell text is authoritative and whitespace-normalized; cosmetic/inline markup is removed. Effective spans are clipped to available rows, with rowspan zero scoped to its source row group. Oversized connected groups use clipped spans and blank covering cells; normalized source text is not duplicated. Metadata records source-cell coordinates/effective spans and half-open code-point offsets independently of synthetic repeated headers. Rechunking removes nested provenance.

Both text and serialized HTML must fit maxCharacters. Leading headers repeat by default when budget and column origin permit; repeated markup is included in the hard budget. Oversized body-cell splitting reserves header room when practical. Whole structural units take precedence over forcing repetition. skipTableChunking preserves isolated table objects unchanged and is an explicit budget exemption. For impractical windows below 50, oversized HTML falls back to text-only table chunks, matching the pinned upstream threshold. Impossible escaped-cell/span overhead fails explicitly.

Validation budgets: 16,777,216 HTML code points, 100,000 rows, 1,000,000 source cells and materialized logical positions, 10,000 columns, depth 256. Malformed metadata/spans, overlapping spans, nested table structures and nonempty captions fail explicitly unless pass-through is selected. Native partitioners already emit outer-table-only structure and separate HTML captions. These are parsing limits, not memory/deadline guarantees.

`docweave-integration-tests/src/test/resources/table-chunking-goldens.json` records eight real pinned-upstream runs with the HTML helper source hash. Four cases match supported upstream fields after normalizing UUIDs and omitting DocWeave extension metadata. Four deliberately assert a measured difference: the pinned upstream row/cell accumulators emit HTML larger than maxCharacters, while DocWeave changes segmentation to honor both budgets without losing content. Original reference outputs are retained rather than replaced with Java outputs. Header repetition and blank span carries are locally specified and not covered by an exact upstream-parity claim. The current reference was rerun on supported Python 3.13.16.

Java regression tests include native HTML/Markdown/CSV/TSV/DOCX pipelines, row/cell splitting, Unicode escaping, optional headers/provenance, pass-through identity, source-cell reconstruction, large/zero/clipped spans, concurrent reuse and seeded tables with merged-cell geometry. These tests are evidence for the documented local contract, not full upstream extraction parity.

## DOCX content-fidelity policies

`DocxContentOptions` controls normal notes, comments, text boxes, image handling and accepted/original/all revision selection. Defaults enable supplemental text and image references. Stored notes/comments follow body content and precede optional footers; target metadata links to referencing source element IDs. Text boxes/images follow their containing element, with parent IDs and code-point anchors. This is explicit stored-part order, not rendered page order. Separator notes are omitted. Inline reference/revision ranges and comment authors survive JSON/provenance round trips.

An ordered XML body view unwraps content controls at block, row and cell levels, retaining paragraph styles/tables. Inline visitors apply insertion/deletion/move selection, read cached field results and collect original instructions without executing them. Simple/complex hyperlink fields preserve targets and labels, including split instruction runs. Fields without usable cached results are not recomputed. Table-row insertion/deletion markers are selected; arbitrary Word revision acceptance/layout behavior is not reproduced.

Package media can emit references or bounded base64 bytes/SHA-256, without decoding pixels, executing vectors or fetching external images. The default embedding budget is 4 MiB per image and is applied only in EMBED mode; archive expansion limits continue to apply in every mode. Drawings with neither extractable media nor text produce diagnostics.

Grid gaps become empty logical positions. Horizontal legacy merges and nonempty vertical continuations retain text, links, notes and media anchors by appending continuation content at the merged origin. Cell/column/row/span budgets still apply after merging. Invalid unmatched merges fail instead of losing content. Flattened nested tables preserve their text, links, references and supplemental anchors. Whole-table annotation offsets are removed from table chunks and retained in source provenance.

Numbering metadata supports common decimal, alphabetic, Roman, English ordinal, bullet and none formats, level starts/overrides, normal restart rules and never-restart. Counters continue through body/table lists within a stored part, and table-list labels carry ranges in docx_list_items. Semantic text remains unprefixed. The full international numbering catalog, picture/font-based bullet rendering and complex conditional-style conflicts and complex conflicting style/override cases still require expanded support/evidence. Unsupported formats retain source metadata and a diagnostic.

Regression coverage includes generated Word packages for these behaviors, an independently saved content-rich DOCX fixture, JSON/chunk provenance, image budgets/external references and virtual-thread reuse. Upstream parser parity and human-reviewed real-world coverage remain unverified.

## JVM batch deadlines

Optional `BatchOptions` task/overall durations are a local JVM orchestration contract, not an
upstream parser option or a claim of upstream timing parity. Both default to disabled. Task time
starts at worker entry and covers the entire per-file pipeline; batch time excludes validation
and input snapshotting. Enabled policies preserve input-order results but observe failures and
timeouts independently, returning ExecutionException with BatchTimeoutException for a deadline.
The timeout cause records TASK/BATCH scope, input index (-1 for BATCH) and configured duration.

Timers and elapsed checks use monotonic time. Failure/interruption requests cancellation and
executor shutdown without indefinite waiting, but cannot guarantee immediate resource release,
force noncooperative code to terminate, or undo side effects. Legacy calls retain input-order
failure observation and close-and-wait cleanup. Tests cover virtual-thread/window bounds, scope
and index, whole-batch versus per-worker timing, cancellation, later failures, caller interruption,
noncooperative code, concurrent reuse and raw/saved-document integration. Timing policy tests do
not establish real-time scheduling guarantees or universal third-party parser interruptibility.


### Incremental batch delivery

Interface methods `BatchProcessor.forEachOrdered`, `DocumentBatch.partitionAndChunkEach` and
`ElementFileBatch.chunkEach` provide lazy, bounded input-order delivery on the caller thread.
The hidden runner reuses virtual-thread task/deadline machinery without accumulating outputs.
The caller owns and closes input sources; callbacks are sequential and provide backpressure.
Delivered prefixes are not rolled back. Failures are independently observed even without deadlines;
cleanup interrupts without indefinite waiting. Blocking caller-side iteration/delivery can delay
batch deadline observation. Per-document materialization remains. This is a local JVM execution
contract, not upstream streaming/timing parity. Existing custom implementations must opt in by
overriding the new default methods, which explicitly reject unsupported streaming.


### Interruption behavior

Built-in chunking now rejects interrupted work with CancellationException, preserves the interrupt
flag, and returns no partial list. Native partitioning retains InterruptedIOException. Batch worker
exceptions remain ExecutionException causes; a competing deadline may expose BatchTimeoutException.
Incremental results already delivered are not rolled back. These JVM execution semantics do not imply
upstream cancellation parity, hard deadlines, or interruptibility within third-party parser/codec calls.


### Saved-element JSON resource policies

`JsonReadOptions` is an immutable value record accepted by interface factories `ElementJson.create`
and `ElementFileBatch.create`. Default reads now enforce 16 MiB encoded bytes, 100,000 elements,
container depth 128 and 16 Mi UTF-16 units per string. This intentionally rejects formerly unbounded
saved inputs; callers can select larger explicit limits. Native PartitionOptions remain independent.
The hidden bounded-input helper checks actual reads rather than trusting file size alone. Jackson
is configured per codec without global state and decodes one element object at a time; root arrays,
exact supported fields, metadata shapes, strict file UTF-8 and rejection of trailing documents remain.
Lists and individual metadata snapshots still materialize. Byte/count errors are IllegalArgumentException;
parser constraints use Jackson exceptions. File interruption becomes InterruptedIOException, string
interruption CancellationException, with flags preserved. Writes and fixed provenance policies are
separate; an unbounded write need not fit default read limits. No upstream resource-policy parity
or heap/time bound is claimed.


### DOCX numbering-style links

The hidden definition resolver follows numStyleLink to numbering styles, including basedOn inheritance,
and resolves the target numbering instance before applying inner-to-outer instance overrides. It keeps
instance-local counters for aliases, overrides, multilevel definitions and non-decimal formats.
Direct single-level decimal instances without style links or overrides share a counter by abstract
definition within a stored part. Source numId metadata remains unchanged. Paragraph pStyle level associations use the closest matching
paragraph style when no direct paragraph level is supplied; inherited ilvl/default zero remain a
fallback when no association matches. This fallback preserves prior Word-oriented behavior rather
than claiming strict OOXML/Word equivalence. See [numStyleLink](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.numberingstylelink?view=openxml-3.0.1),
[pStyle](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.paragraphstyleidinlevel?view=openxml-3.0.1)
and [Word's ilvl deviation](https://learn.microsoft.com/en-us/openspecs/office_standards/ms-oe376/02932249-a32e-48ac-8237-6f91b4182403).
Numbered headings retain Title semantics and gain label/counter metadata. Invalid/missing/cyclic or
128-hop-exceeding link chains retain text/source references with docx_numbering_unresolved and a
DOCX_CONTENT_OMITTED diagnostic; no inferred label is emitted. styleLink is descriptive, not a
forward link. Generated DOCX regression fixtures cover supported paths; broader human-reviewed
Word-produced evidence, complex conditional-style conflicts, international formats and
picture/font bullets remain limits. Upstream parser parity has not been established for these cases.


### Implicit default paragraph style

For paragraphs without pStyle, DocWeave selects a flagged default paragraph style and resolves its
basedOn chain using the same outline/numbering policy as explicit paragraph styles. Explicit styles,
including missing references, do not trigger fallback. Direct outline/numbering/level properties and
numId=0 suppression keep precedence. True/on/1 enable the default flag; false/off/0 and absent flags
do not. Other style types and the name Normal do not implicitly select a default. Multiple flagged
paragraph defaults recover deterministically using the last declaration in part order; no Word parity
claim is made for malformed style sets. See [Microsoft's paragraph-style retrieval example](https://learn.microsoft.com/en-us/dotnet/standard/linq/retrieve-paragraphs-styles).
Effective style metadata is docx_style plus docx_style_source=default for implicit selection; table-list
ranges carry those fields in docx_list_items. Default selection/definition caches are document-local;
counters remain stored-part-local. Original text and explicit-style metadata conventions are retained.
Generated-DOCX tests cover supported paths; independent human/Word rendering validation is pending.
This named default style is distinct from docDefaults; the supported document-default semantic properties are described below.


### Document-default semantic paragraph properties

DocWeave now reads docDefaults/pPrDefault/pPr as the lowest-precedence source for outlineLvl and
numPr's numId/ilvl, below direct formatting and named style inheritance. Numbering subproperties
merge individually; style-associated levels take precedence over the document base level. Missing
explicit style references still receive these base properties without selecting a named fallback.
Direct numId=0 suppresses numbering; invalid effective list levels fail MALFORMED_DOCUMENT, while
higher-precedence valid values shield overridden invalid defaults. Application defaults are not
invented when no stored property exists. See [the OOXML document-defaults description](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.docdefaults?view=openxml-3.0.1).
Metadata docx_document_defaults lists effective source properties (outline_level, num_id, list_level),
also within table docx_list_items. No marker is emitted if defaults contribute none of these properties.
JSON and chunk provenance retain the marker; no text prefix or synthetic style ID is added. Only
semantic outline/numbering properties are interpreted: run defaults, spacing, alignment, visual table-style formatting and layout are outside this policy. Stored-part counters remain independent.
Regression coverage uses owned generated DOCX inputs, not independent Word-rendering/upstream parity.


### Whole-table paragraph-style inheritance

Cell paragraphs inherit semantic outline/numId/ilvl from the selected table style, below direct/named
paragraph formatting and above docDefaults, with the direct-body exception described below.
Explicit table style references are preserved; an absent
reference may use the last declared flagged default table style. Missing explicit references do not
fall back. Nested tables resolve their own style rather than borrowing the containing table's style.
Cycle-safe, interruptible basedOn traversal is capped at 128 hops; unresolved chains discard partial
layers and preserve text with a diagnostic. Effective lookup uses derived-to-base wholeTable numbering
layers, then derived-to-base general pPr layers; wholeTable outline overrides are ignored. This local stored-property contract is not complete Word
style-conflict or rendering parity. See [conditional style ordering](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.tablestyleproperties?view=openxml-3.0.1).
Tables expose selected style metadata and docx_table_style_paragraphs Unicode ranges for contributed
properties; outline values are retained without converting table text into separate Title elements.
List annotations retain docx_table_style_properties and share stored-part counters. New whole-table
range metadata is dropped from chunks and retained in original-element provenance when enabled.
General properties, wholeTable numbering and supported positional semantic conditions are implemented;
wholeTable outline overrides are ignored. Unknown types
retain an omission marker/diagnostic. Positional membership follows the explicit stored-grid policy
below, with no claim of Word application-default or visual-layout parity.
Visual-only formatting remains outside this semantic policy. No external resources are fetched and
no dependency on the full OOXML schemas is added; conditional pPr is read from retained XML.


### Positional semantic table regions

Semantic layers apply in increasing precedence: wholeTable, vertical bands, horizontal bands,
first/last columns, first/last rows, then NW/NE/SW/SE corners. Derived-to-base properties merge within
each region. Annotation region order remains unchanged and does not specify application precedence.
The hidden region resolver computes logical footprints for grid gaps/spans, declared width and legacy
horizontal/vertical merges before extraction; all stored runs in a merged cell share that footprint.
It preserves resource budgets and typed diagnostics. Nested tables have independent shape/settings.
Look masks and named flags select condition families; row tblPrEx settings precede direct table then
style-chain settings, merging individual flags. Default look is OOXML 0000, not Word's 04A0; band
sizes must be present in the effective settings to enable semantic stripes on that axis; absent
sizes and explicit zero disable banding. A present size element without a value retains the existing
size-one fallback. First/last enabled edge lines with resolved paragraph branches are excluded
from inferred stripes, with a qualifying first edge shifting the stripe origin. Missing branches
do not reserve stripe positions. See [tblLook](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.tablelook?view=openxml-3.0.1),
[Word's default deviation](https://learn.microsoft.com/en-us/openspecs/office_standards/ms-oe376/14b3f20d-4017-43c8-909c-d70620034b95)
and [conditional precedence](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.tablestyleproperties?view=openxml-3.0.1).
Stored cnfStyle overlays inferred membership row then cell then paragraph. A 12-bit binary mask sets
all membership bits; named true/on/1 or false/off/0 flags override individual bits. Explicit corner
bits remain independent; unspecified corners derive from final edge memberships. Look flags still
gate families, and both edge options are required for corners. This deterministic cached-flag policy
is documented separately from Word's handling of stale/conflicting cache data; see [cnfStyle](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.conditionalformatstyle?view=openxml-3.0.1).
Source ranges record ordered regions membership; JSON/provenance retain it, while whole-table ranges
remain excluded from chunk metadata. Recognized disabled conditions no longer produce omission
warnings; unknown semantic types still do. Invalid masks/booleans/band sizes fail MALFORMED_DOCUMENT;
logical geometry budget violations fail TABLE_LIMIT. Visual/bidi ordering, rendered pagination/header
repetition, compatibility-mode deviations and independently reviewed Word-authored evidence remain
limits. Passing generated fixtures does not establish Word-rendering or upstream parser parity.


### Official forms corpus

Ownership-v1 has migrated into the development-only Java/JUnit validation module.
Saved Python evidence is retained; current Maven checks need no Python for this
oracle. See [Java migration evidence](../benchmarks/review/java-validation/README.md).
Reference currency is tracked independently in [upstream status](upstream-tracking.md).

The opt-in [ownership-v1 diagnostic](../benchmarks/review/docx-paragraph-ownership/README.md)
adds disjoint paragraph occurrences, ordered body owner/type matching and text-box
parent-ID checks. Two owned fixtures and both external forms pass. AlternateContent
selection and blank text-box hosts reject explicitly. This is a stored-XML oracle
for a bounded subset, not renderer ground truth or cell-level reading-order evidence.

Two hash-pinned blank consultation forms from the Law Commission and Scottish
Government add different real-world templates, stored content controls and
header/footer package variants. All 56/25 nonblank body paragraph substring checks
pass under default and stored-part policies, with no warnings. These are
candidate-only diagnostics: no upstream comparison, control interaction, ownership,
reading-order, symbol glyph or rendering accuracy is established. External binaries
remain fetch-only and human review is pending. See the [forms findings](../benchmarks/corpus/docx-forms/README.md).

### External conditional-style corpus audit

The candidate-only [style corpus audit](../benchmarks/corpus/docx-styles/README.md) adds three pinned
Apache POI test samples to the two published NRS reports. Unchanged-source inventory covers five
files/14 tables, six stored visual conditional definitions and zero conditional outline/numbering
definitions. Thus the originals do not independently establish semantic conditional-style correctness.
Fifteen explicitly derived probes replace table/control settings, clear cached cnf flags, and test
enabled/disabled/direct-override behavior while preserving extracted type/text sequence. Four sources
provide positive text cases, one is an empty negative control; 603 source ranges exercise the enabled
policy and revert to whole-table outline when disabled. Exact formatting, authored cache behavior,
rendered Word output and upstream parser parity remain unverified. The report records source/tool/
runtime fingerprints and aggregate evidence without external binaries or full text in the repository.

Independent DOCX semantic-style validation now has a [six-case Word review packet](../benchmarks/review/word-style-cases/README.md). Versioned Word observations and repeat checks are collected; independent human approval remains pending. Saved replay/comparison now runs in the Java/JUnit validation module with raw evidence pins and historical-difference parity checks. A bounded seven-file external search found no conditional numbering/outline definitions.

Native text cancellation coverage includes periodic checks during Unicode normalization, blank/letter
scans, decoded binary-control validation, and TXT sizing/boundary searches. TXT line discovery is lazy
and retains regex `\R` boundary behavior, including CRLF and trailing empty lines. These checks preserve
InterruptedIOException and the thread flag; no partial partition result is returned. Whole documents
and individual lines/paragraphs still materialize, and library regex/codec/parser/bulk calls remain
indivisible. This is not a streaming or bounded-heap guarantee.

Decoded Text source loading counts UTF-8 bytes and validates surrogate pairs without a full encoded
payload. A bounded prefix retains existing byte-signature and 8 KiB detection behavior. The original
String is reused except for leading-BOM removal; diagnostic byte counts still include that BOM.
Declared charset options and HTML/XML encoding declarations do not reinterpret decoded Text.
Malformed surrogates retain INVALID_ENCODING, byte overflow retains INPUT_TOO_LARGE, and cooperative
interruption retains InterruptedIOException. File/byte loading and parser/output materialization are
unchanged; this is an allocation reduction, not streaming or a bounded-heap contract.

File loading reads an already-open channel in at-most-8-KiB chunks. Channel size is a bounded
allocation hint, not the byte-count authority: EOF/actual bytes govern the result, exact-limit EOF
is accepted, and one extra byte triggers INPUT_TOO_LARGE. Stable files avoid growth and final-copy
arrays; changing files may still grow/trim buffers. Payload arrays contain exact bytes with no
unused capacity, preserving binary DOCX and BOM/signature behavior. The loader closes its channel
on success/failure. Interrupted checkpoints preserve the flag; a channel read may itself throw a
provider-specific IOException. No atomic snapshot, streaming parse or bounded heap is promised.

ASCII UTF-8 payloads use a verified-byte path that avoids a document-sized intermediate character
buffer. This path includes all ASCII bytes; existing control validation still rejects disallowed
controls after decode. Non-ASCII UTF-8, malformed data and other/stateful charsets retain the strict
reporting decoder. Tests compare random bytes and malformed suffixes with that decoder. ASCII scan
checkpoints preserve InterruptedIOException and the flag; codec/String construction is indivisible.
Allocation/live-heap probes are described in benchmarks/decoder-and-partition-memory.md and do not
establish streaming, exact retained object-graph sizes or a heap ceiling.

Text classification/list cleaning now scan markers and count words without per-call Matcher or
word-split arrays. Behavior is compared against historical regex/string rules, including ASCII
regex whitespace/digits, Unicode body text and greedy cleaning of whitespace-only bullet tails.
Title word counting retains leading/interior empty fields and ignores trailing ASCII spaces.
This optimization changes allocation behavior, not classification or output contracts.

Native deterministic IDs preserve the existing filename/text/None-page/sequence UTF-8 message
and first32 lowercase SHA-256 hex digits. A document-local helper reuses its engine and directly
formats the first16 digest bytes; it does not change IDs, metadata snapshots or sequence handling
on failed validation. Encoding still happens after concatenation to preserve surrogate pairs
across filename/text boundaries. Full hash-input copies remain a materialization limit.

Large native identity messages now encode incrementally with bounded JDK UTF-8 scratch buffers.
Surrogate pairs spanning components/buffers and malformed-surrogate replacement match the legacy
whole-message getBytes hash. The first eight small messages may use bounded whole-message encoding
before buffer promotion. Partial codec/digest state is reset on failure or interruption; flags remain
set. Hash format and immutable metadata ownership are unchanged. Full document/parser/output memory
remains outside this scratch bound; bounded encoding may exchange additional codec calls for memory.

### Word-observed single-level decimal sequence sharing

Owned Word16.0 build16.0.20430 controls expose a continuous sequence across two unmodified
numIds referencing one single-level decimal abstract definition. Distinct abstract definitions or
an explicit startOverride separate sequences. Direct paragraph numbering and table-style numbering
show the same behavior. The resolver now adopts this narrowly scoped sequence policy while preserving
original numId metadata. Tagged counter identities prevent collisions between abstract and instance IDs;
state remains local to the stored part. Definition-selected identity is cached per document.

The change excludes multilevel definitions, non-decimal formats, numStyleLink/styleLink definitions
and all instance overrides. Their broader cross-instance behavior is not established by these controls.
Regression tests compare labels against four pinned Word measurements and exercise alias isolation,
restart isolation and existing multilevel/non-decimal policy. Original candidate and Word evidence
files remain historical snapshots and are not regenerated to conceal earlier differences.

This adopts measured behavior for a bounded subset; independent human approval and universal Word
parity remain unclaimed. Outline/banding/conditional-precedence discrepancies from the same packet
are separate local follow-up work. No further VM collection is currently required.

### Absent semantic band sizes

The owned unsized-bands input and its repeat measurements from Word16.0 build16.0.20430 expose
body outline9 in every cell. The resolver now disables horizontal semantic stripes when no row-band
size is found in row exceptions, direct table properties or the inherited table-style settings.
The same absence rule is applied to the column axis as a consistent local policy; the saved Word
input establishes horizontal behavior only. This is not a claim about all visual band formatting.

Explicit sizes retain stripe selection and inheritance; explicit zero suppresses inherited sizes.
Stored conditional flags cannot reactivate a disabled axis. A pinned-file regression checks the
observed horizontal outlines and source hash. Local tests cover both absent axes, cached flags,
axis independence, explicit sizes, inheritance and zero. Existing precedence coverage now declares
size1 explicitly so its purpose remains checking precedence rather than an absent-size default.

Historical candidate/Word evidence files remain unchanged. Other discrepancies (edge flags without
a corresponding branch, overlapping edge precedence, wholeTable and direct body-outline behavior)
remain separate follow-ups. No additional VM collection is needed for this change.

### Edge flags without paragraph branches

The pinned edge-flag-without-branch input enables firstRow but defines only horizontal band
paragraph branches. Word16.0 build16.0.20430 exposes outlines0/1/0 by row, including the first
row. The resolver now reserves an enabled edge outside the inferred stripes only when the
effective paragraph-style conditions contain that edge branch. Inherited branches qualify;
missing branches do not shift the origin or exclude an edge line. The reservation rule applies
symmetrically to first/last rows and columns as a local policy; the saved Word evidence directly
establishes only the first-row case.

Branch availability is selected by the style resolver and supplied to the geometry resolver as
an immutable set, keeping XML inheritance separate from stored-grid calculations. Edge/corner
membership, tblLook gating and stored cnfStyle overlay order are unchanged. Visual-only branches
without paragraph properties do not qualify under this semantic policy; their influence on Word
layout has not been established by these inputs. Empty paragraph branches do qualify.

A pinned-file regression checks the observed outlines and source hash. Local tests cover all
four edges, present/absent inherited branches and enabled/disabled look flags. Existing size,
geometry, cached-flag and precedence checks remain in place. Historical observations/candidates
are unchanged; human review and broader Word parity are still unclaimed.

### Row/column semantic overlap

The owned row-column-overlap input and its clean repeat on Word16.0 build16.0.20430 expose
first-row outline4 at the top-left overlap, rather than first-column outline5. Semantic table
paragraph layers now give rows precedence over columns. Corner layers still outrank both;
within each region derived styles outrank base styles. Missing properties still fall through
to lower-priority layers, so a row outline does not suppress numbering available only from a column.

Application precedence is separate from the stable canonical order of annotation `regions`.
No range-order or public metadata schema change is introduced. A pinned-file regression checks
all nine observed outlines, input SHA256 and unchanged overlap membership ordering. Local tests
check inherited row precedence over a derived column and per-property numbering fallthrough.
Last-edge symmetry is local policy: only the first-row/first-column outline overlap is directly
established by the saved Word measurements. Other property/edge combinations remain bounded local
behavior rather than a claim of full Word parity. Historical evidence is unchanged and human review
remains pending. WholeTable and direct body-outline behavior is documented in the following property-specific corrections.

### WholeTable outline overrides

The owned whole-table-branch input has general body outline9 and conditional wholeTable outline6.
Word16.0 build16.0.20430 exposes body outline9 in every cell, identically on repeat collection.
The resolver now ignores outlineLvl in wholeTable paragraph branches, including inherited ones,
while preserving general table-style outline properties and active positional conditions. An isolated
wholeTable outline no longer creates semantic source ranges. Source XML and text are not changed.

This correction is deliberately property-specific. Existing wholeTable numPr inheritance remains
unchanged and is not independently established by the saved outline-only control. Do not interpret
this as ignoring every wholeTable property or validating numbering behavior there.

The pinned-file regression checks input hash and all nine observed outlines. Local tests cover
inherited general outline/numbering beneath ignored wholeTable outlines and an outline-only branch
without general properties. Region/geometry tests use general pPr for their fallback outline so they
continue to isolate geometry and precedence. Original Word/candidate evidence is unchanged. Human
review and universal Word parity remain unclaimed. The direct body-outline9 exception is described below.

### Direct body outline in conditionally styled table cells

Owned Word16.0 build16.0.20430 controls show explicit direct outline9 (body) yielding to
firstRow outline4, both with and without numbering. The plain-table body control remains body9;
direct heading0 and3 override the conditional outline in all four heading controls. The resolver
now adopts this body-specific exception: direct9 yields to the highest-precedence active positional
heading, excluding wholeTable and general base layers. Direct heading values retain priority, and
numId0 still suppresses numbering independently. Paragraphs outside these table conditions retain
their previous behavior. This policy extends consistently to other supported positional regions;
the saved Word controls directly establish the first-row case only.

The style resolver separately selects the positional outline, so the policy resolver does not
confuse a general table heading with a conditional one. A positional body/invalid outline does not
trigger the exception. Applied conditional outlines receive normal table-style source ranges.

Pinned regressions verify the original direct-override outline and numbering suppression, both
plain/conditional body controls and all four direct-heading controls against collected values and
input hashes. Heading controls use the internal effective policy because the candidate comparator
cannot expose non-body direct table headings. Local coverage checks inactive conditions and general
headings do not displace direct body. Historical observations/candidates remain unchanged; these
measurements are build-specific object-model evidence, not independent human approval or universal
Word parity. No further VM collection is currently planned.

### Unsupported DOCX symbol/import diagnostics

Selected w:sym run symbols and w:altChunk body imports now emit DOCX_CONTENT_OMITTED once
per occurrence instead of disappearing without a diagnostic. Messages are fixed descriptions;
font names, character codes, relationship IDs, URLs and payload text are not interpolated. No
glyph, placeholder text, import rendering or new semantic element is invented. Surrounding text,
IDs and Unicode link offsets are unchanged. No new source-range metadata is introduced for omitted
content; callers must retain original inputs if those source details are needed.

The ordered body adapter receives a required diagnostic sink and reports imports only in traversed
content. It does not resolve relationships or open embedded import payloads, including internal,
external and missing relationships. Inline symbol handling similarly reports without mapping fonts.
Revision selection and header/footer/note/comment/textbox options apply before reporting omissions.
This covers selected body/table/content-control and supplemental contexts through shared visitors;
it is not a guarantee that every possible unsupported OOXML construct is diagnosed.

Seven owned regression tests cover repeated symbols, symbol-only paragraphs, IDs/text/link offsets,
internal imports, external/missing imports, table/content controls, revision selection and enabled/
disabled supplemental parts. The two audit probes retain their text and now each report one omission
warning. Existing archive/resource/cancellation checks and saved Word comparisons remain in force.

### Nested-table preservation (HTML, Markdown DOM and DOCX)

PartitionOptions now includes NestedTableOptions, defaulting to FLATTEN. Previous full and legacy
constructors delegate to that default. The builder remains an interface; its new default setter
accepts flattening for legacy custom implementations but explicitly rejects preservation unless
implemented. The built-in builder stores immutable policy snapshots. Public policy values are records and
enums, while shared accounting/dispatch helpers remain hidden in the JDK-only core.

PRESERVE_METADATA is supported by **HTML, Markdown EXTRACT_DOM and DOCX**. Partitioner exposes a default-false
supportsNestedTablePreservation capability; HTML, Markdown and DOCX override it. Markdown rejects AST
modes explicitly and requires a capable optional HTML extraction service. TXT/CSV/TSV/XML reject the option explicitly. Facade dispatch and each built-in SPI entry reject an
unsupported request with UNSUPPORTED_OPTION; no parser output or silent flattening is returned.
Custom strategies advertising true are responsible for honoring the policy and its budgets. Common
source loading/detection may occur before facade dispatch; direct built-in SPI rejection occurs before
content parsing. The new error category is appended, preserving prior enum ordering.

Initial preservation budgets are256 children per document, depth16 and1Mi metadata code points.
Configured limits must be positive, with depth at most256. A hidden document-local reservation helper
checks count, depth and cumulative payload atomically with overflow-safe arithmetic; failed or
interrupted reservations do not consume capacity. Empty children still consume a count. The shared descriptor writer validates paths, backward parents,
contained root intervals and child-local links before reserving or copying. It measures string keys/values
by code point plus one unit per scalar/container, including repeated text/HTML/link/caption/path copies;
the fixed root envelope is excluded. HTML preflights child count/depth, bounds snapshot strings while
rendering, and charges combined payload before freezing. These measures do not guarantee heap usage.

The keys nested_tables and nested_tables_version are reserved source-relative metadata. All generated
table outputs and composites drop them from direct metadata, including canonicalized fitting tables,
structural fragments and text fallback. Source snapshots retain them through orig_elements when
requested. Disabling provenance omits them; exact skipTableChunking pass-through retains the original
Element and metadata. JSON round trips still preserve these extension values. No fragment remapping is implemented. Default nested-table fixtures and saved Word comparisons remain unchanged.

HTML emits a flat preorder list of immutable descriptors on each root Table, retaining child-owned
rows/cells/spans, captions and local links. Paths use source logical origins accounting for span
occupancy. Intervals refer to root flattened text in Unicode code points; child text has its own row/cell
separators. Root text/HTML/IDs/order/warnings are unchanged. Empty children get zero-width intervals;
entirely empty roots remain omitted. Opt-in source tables use TableLimits.defaults(); oversized logical
spans used for preservation fail TABLE_LIMIT, overlap or unsupported child ownership fails MALFORMED_DOCUMENT. No browser
layout, external fetch or DOCX semantics are implied. See the [contract](nested-table-contract.md).


Markdown DOM preservation delegates the original policy/context and custom TableLimits to the first
HtmlExtraction service. The service capability defaults to false for compatibility with older
providers. An incapable first provider fails UNSUPPORTED_OPTION rather than switching to another;
missing HTML fails UNSUPPORTED_FORMAT. AST mode/provider checks for preservation run before content
parsing/rendering/emission. Default FLATTEN retains its prior error precedence. The Markdown module
still has no compile/transitive HTML dependency, and standalone AST extraction works unchanged.
Paths/ranges refer to the combined rendered DOM rather than Markdown source offsets. Both Markdown
AST and HTML depth policies remain enforced. Owned mixed pipe/raw/code fixtures check unchanged root
IDs/text/HTML/hierarchy/warnings, child grids/captions/local links, filtering, exact JSON snapshots,
custom limits and source-only chunk provenance. This adds no claim of upstream parser/layout parity.


DOCX preservation captures existing recursive results once, retaining child text/HTML/spans/links,
stored table captions and whitelisted child-local semantic metadata. Separate sidecars track root
intervals through table/paragraph separators and merge composition. Source-preorder descriptors may
have nonmonotonic root offsets when continuation content moves to an earlier merged origin. Source
paths include grid gaps and paired owner coordinates when needed. Snapshot numbering/style results
are reused, with no extra counter consumption or emissions. Root IDs/text/HTML/order/warnings stay
unchanged; empty children have distinct zero-width descriptors. DOCX's existing empty-root emission
policy remains in effect.

Budget accounting spans every enabled stored part, including notes/comments/text boxes and optional
headers/footers. Count/depth preflight precedes child descent; incremental retained-payload preflight
precedes snapshot retention and complete descriptor measurement precedes immutable copying. Native
models/render strings still materialize under archive/XML/table limits. The [contract](nested-table-contract.md)
lists the source_metadata whitelist and limits. Reference/revision/list/style/anchor offsets are
child-local; source snapshots contain no XML nodes or recursively embedded nested tables. Cached
fields are not executed. This adds stored-structure support rather than Word layout parity.


The [owned nested-table model corpus](../benchmarks/nested-table-validation.md) adds72 deterministic
cross-format cases with independent hierarchy/path/grid/token oracles and link/JSON/default-output
checks. Default child count256/depth16 acceptance and257/17 rejection are checked across all three
adapters, as are exact payload budgets and a larger escaped-Unicode snapshot workload. A compound
DOCX horizontal/vertical merge case checks grandchild prefixes and text-box anchor composition.
These generated inputs supply structural regression evidence, with no new browser/Word/heap claim.


## Independently maintained complex DOCX corpus

The fetch-only [Apache POI/LibreOffice complex corpus](../benchmarks/corpus/docx-complex/README.md)
adds four immutable source-pinned samples: two nested-table inputs, one inserted/deleted-text input
and one main-body textbox. Ownership checks cover 203 accepted paragraphs per stored-part mode,
including the textbox parent relationship. Opt-in preservation retains eight nested descriptors
across the two table inputs while leaving root elements/warnings unchanged. Five distinctive long
deleted fragments absent from accepted source are also absent from body output; short/ambiguous
fragments and full tracked-change fidelity are not assessed.

These are independently maintained regression fixtures, not representative published documents or
proof of authoring-application provenance. Existing omission/flattening warnings remain explicit;
matching accepted paragraphs does not establish glyph, layout, table cell order or rendering fidelity.
No upstream reference run, Word/rendering review, production parser change or reference repin was made.


The [complex-corpus omission audit](../benchmarks/review/docx-omission-audit/README.md) confirms that
five drawing warnings correspond to textless VML rectangles marked as horizontal rules, and the
LibreOffice sample explicitly references a missing TableGrid style. Vector rendering/shape categories
are outside the current text model. An unresolved explicit table style does not inherit the default
table style as a substitute. Owned controls retain surrounding text/IDs, emit no fabricated image or
page break, and require exact diagnostic causes. This audit changes validation, not parser semantics.


Native provider layouts optionally infer section rows with
`PdfNativeLayoutOptions.LeadingRow.SOURCE_NUMERIC_OR_SECTION_ROW`. The default remains
`PROVIDER_ONLY`; the earlier numeric-only policy is unchanged. This enum addition may
require an extra branch in exhaustive client switches. It changes neither the options
record shape nor provider interfaces. Added source-derived geometry is identified in table
metadata and is subject to existing ownership, topology and resource limits. Geometry and
colon punctuation cannot establish semantic membership in every document; use the default
when retaining exact provider bounds is required. See the [PDF policy and limitations](pdf-support.md#explicit-native-leading-section-rows-unreleased).


`PdfOptions.withImageClipBounds(true)` opts into conservative image clipping metadata.
Existing constructors remain available and default to false. Adding the boolean record
component changes reflective schemas, canonical constructor shape and record-derived
serialization/equality/hash/toString behavior. Source image payloads and occurrence defaults
remain unchanged. `PdfCoordinateConverter` converts the new nested bounds where present;
empty or unbounded states contain no coordinates. See [the field contract](pdf-support.md#conservative-image-clip-bounds-unreleased).


`PdfOptions.withImageGroupContext(true)` opts into enclosing painted transparency-group
source snapshots. Defaults remain false and the prior 15-argument constructor is retained.
The new sixteenth record component changes canonical/reflective record schemas and
record-derived serializers, equality, hash and textual representation. Current-stream
image state, normalized PNGs and source occurrence accounting are unchanged. Group
snapshots are scalar metadata, so coordinate conversion retains them without scaling.
Absent/malformed isolation and knockout values follow PDFBox's false fallback and are
not distinguished as raw source syntax. See [the field contract](pdf-support.md#enclosing-image-transparency-group-context-unreleased).


`TesseractOptions.withLineSplitting(WIDE_GAPS)` explicitly changes recognized words' returned
line grouping and boxes. Default engine-line grouping is unchanged. The 14th record component
retains previous constructors but changes canonical/reflective schemas and record-derived
serialization/equality/hash/text. Added lines still count against the same returned-line
limit. Source recognition is unchanged; callers must not assume each fragment is a complete
semantic row. See [the PDF OCR policy](pdf-support.md#explicit-ocr-wide-gap-line-splitting-unreleased).


`PdfOptions.withCurvedClipEnvelopes(true)` adds conservative curved-path envelopes to the
graphics visitor. The 17th record component retains previous constructors but changes
canonical/reflective schemas and record-derived serialization/equality/hash/text. Native
rule eligibility can change independently of image metadata selection; the separate text
visitor is unchanged. The default remains false. See [the contract](pdf-support.md#curved-clipping-envelopes-unreleased).
