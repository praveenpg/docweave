# Markdown support and qualification

This page describes the Markdown dialect DocWeave extracts, the raw-HTML modes, inline metadata, opt-in syntax
extensions and the evidence behind them (issues #57–#60). It describes observed behavior on a small owned corpus and
focused tests. It does not claim parity with Unstructured or any other Markdown implementation.

## Dialect

`docweave-markdown` parses CommonMark with commonmark-java 0.30.1. GFM pipe tables are on by default
(`MarkdownOptions.tables`). Everything else follows CommonMark. Examples: two-space-indented items nest; a trailing
backslash is a hard break; a pipe inside a code span still separates table cells unless escaped; a ragged table row
gets empty cells added or its surplus cells dropped, as the GFM specification requires. Setext headings, reference links
and images, entity references, HTML-in-Markdown block rules and lazy continuation lines follow the CommonMark
specification. Unstructured converts Markdown with Python-Markdown, a different dialect, so some outputs differ by design.
Those differences are listed below.

## Raw HTML modes

| Mode | Use it when | Behavior | Requirements |
| --- | --- | --- | --- |
| `PRESERVE_TEXT` (default) | Raw HTML is rare or must be visible for review | Raw blocks become `UncategorizedText` with literal markup; inline tags stay literal inside prose; one `RAW_HTML_PRESERVED` warning per document | none |
| `SKIP` | Markup is noise and losing HTML-only content is acceptable | Raw block nodes are dropped, including any text inside them. Inline tags are removed, but the text between them stays, including text inside `hidden` spans. Raw anchors lose their link metadata | none |
| `EXTRACT_DOM` | Documents mix substantial HTML with Markdown (embedded tables, HTML headings, `<details>`) | Renders the whole document to HTML and extracts the combined DOM with the HTML strategy, so Markdown and HTML headings/lists share one hierarchy. `hidden`/`aria-hidden`, script, style and comments are removed; CSS (`style="display:none"`) is not evaluated. Malformed wrappers are repaired by jsoup with an `HTML_RECOVERED` warning | `docweave-html` on the classpath; otherwise `UNSUPPORTED_FORMAT` |

DOM-mode tradeoffs: images contribute alt text as prose without `image_url`/`image_urls`. Code blocks lose `code_info` and
`code_language`. Pipe tables lose `table_column_alignments`. No emphasis, strikethrough or task-state metadata is
emitted. HTML depth, visibility and header/footer policies apply in addition to Markdown AST and table budgets.
Nested HTML tables are flattened with a warning unless nested-table preservation is selected, which only this mode
supports. Prose classification follows the HTML strategy.

## Inline metadata (AST modes)

Links keep `link_texts`, `link_urls`, `link_start_indexes` and `links`, as before. Since #60, bold and italic spans add
three parallel lists:

| Key | Value |
| --- | --- |
| `emphasized_text_contents` | Span text with surrounding whitespace removed |
| `emphasized_text_tags` | `"b"` (strong), `"i"` (emphasis) or `"bi"` (both) |
| `emphasized_text_start_indexes` | Code-point offset of the span in the element text (DocWeave-specific) |

```java
var result = DocWeave.create().partition(DocumentSource.text("Plain **outer *inner* tail** 🧵 *é*.", "a.md"));
// text: "Plain outer inner tail 🧵 é."
// emphasized_text_contents: [outer, inner, tail, é]
// emphasized_text_tags:     [b, bi, b, i]
// emphasized_text_start_indexes: [6, 12, 18, 25]
```

The text, element types, order and hierarchy are unchanged. Spans break at every emphasis boundary, so nesting
produces separate runs with combined tags. A run continues through links, code spans and images inside it. Literal
markers inside code spans and escaped markers are not emphasis. Headings, list items and table cells carry spans.
Table offsets count the tab/newline separators in the table text. Chunking concatenates contents and tags, as it does
for links, and drops the element-local offsets.

These are the intended differences from Unstructured's `emphasized_text_contents`/`emphasized_text_tags`.
Unstructured annotates each HTML text segment, so `*see [the docs](u)*` gives `[see, the docs]` where DocWeave gives
`[see the docs]`. It emits no offsets and no emphasis for tables. Its serialization is not reproduced exactly.
`EXTRACT_DOM` and the HTML format do not emit emphasis metadata.

## Syntax extensions (#59)

`MarkdownOptions.extensions()` is empty by default, so default output is unchanged. Select extensions explicitly:

```java
var markdown = MarkdownOptions.defaults().withExtensions(EnumSet.of(
        MarkdownOptions.Extension.TASK_LIST_ITEMS, MarkdownOptions.Extension.STRIKETHROUGH,
        MarkdownOptions.Extension.AUTOLINKS, MarkdownOptions.Extension.FOOTNOTES,
        MarkdownOptions.Extension.DEFINITION_LISTS, MarkdownOptions.Extension.FRONT_MATTER));
var options = PartitionOptions.builder().markdownOptions(markdown).build();
```

| Syntax | Option | Without the option | With the option (AST modes) | EXTRACT_DOM |
| --- | --- | --- | --- | --- |
| Task lists `- [ ]`/`- [x]` | `TASK_LIST_ITEMS` | Marker stays in the text | Marker removed; `task_list_item_checked` on the item's first element | Marker removed; no state metadata |
| Strikethrough `~~x~~` | `STRIKETHROUGH` | Tildes stay in the text | Tildes removed; `strikethrough_texts`, `strikethrough_start_indexes`. Single tildes stay literal | Tildes removed; no span metadata |
| Bare URLs, `www.` hosts, e-mail addresses | `AUTOLINKS` | Plain text | Text unchanged; ordinary link metadata (`http://` and `mailto:` added to the URL) | Same links |
| Footnotes `[^label]` | `FOOTNOTES` | CommonMark reads `[^x]: text` as a link reference definition: the definition's text is dropped and `[^x]` becomes a link labelled `^x` | See [Footnotes](#footnotes) | Reference rendered as its number; notes rendered as an ordered list after the content, each ending with `↩` |
| Definition lists `Term` / `: Definition` | `DEFINITION_LISTS` | One paragraph (`Term : Definition`) | See [Definition lists](#definition-lists) | `<dl>` markup extracted by the HTML strategy: terms and definitions become prose elements, without role metadata |
| Front matter `---` … `---` | `FRONT_MATTER` | The block becomes a level-2 setext `Title` | See [Front matter](#front-matter) | Same values and malformed-block handling |

The first four are commonmark-java 0.30.1 modules (BSD-2-Clause); autolinks add `org.nibor.autolink:autolink:0.12.0`
(MIT). Definition lists are a small DocWeave block-parser extension, because commonmark-java has none. Front matter is
read by a DocWeave reader before CommonMark parsing; no YAML library is used. There is no plugin mechanism and no
second parser. Inline footnotes (`^[note]`), heading anchors, `++ins++` and image attributes are not supported.

### Footnotes

- A reference `[^label]` with a matching definition becomes `[n]` in the text, numbered as commonmark-java's HTML
  renderer numbers notes, so `EXTRACT_DOM` shows the same numbers: references outside notes in document order, then
  references inside each numbered note in number order. A later reference to the same note reuses its number. Labels
  match as CommonMark link labels do: trimmed, case-insensitive and with whitespace collapsed.
- The referencing element records three parallel lists: `footnote_reference_labels` (as written),
  `footnote_reference_numbers` and `footnote_reference_start_indexes` (code-point offset of `[n]`). Headings, list
  items and table cells carry them too; table offsets count the cell separators, as emphasis offsets do.
- A definition `[^label]: text` stays where it is written, with its usual element type and heading parent. Its first
  element records `footnote_label` and, when the note is referenced, `footnote_number`. Later blocks of the note
  (indented by four spaces: paragraphs, lists, code) take that element as `parent_id`. A heading inside a note
  outlines only that note: text after the note keeps the heading parent it had before it.
- Malformed input loses no text: a reference without a definition, an empty label `[^]` and a label containing a
  space in a definition line stay literal. A second definition with an already defined label is kept but gets no
  number; references resolve to the first. A note the HTML renderer would not show (nobody references it, a later
  duplicate, a definition nested inside another note, or a note cited only from such notes) keeps its place and text
  and gets no number, and a reference to it stays literal, in every mode.
- Deliberate difference from Python-Markdown's `footnotes`: it moves every note to the end and renders the reference
  as a bare number (`reference1`). DocWeave keeps document order and makes the marker visible as `[1]`, so chunks keep a
  note near its context. `EXTRACT_DOM` follows the rendered HTML and therefore matches upstream's order and text. The
  renderer would omit those notes; `EXTRACT_DOM` keeps their blocks in place as ordinary content instead, and turns a
  malformed `[^…]:` line back into text, so no text is lost.

### Definition lists

- Syntax (PHP Markdown Extra, as in Python-Markdown's `def_list`): one or more term lines, then a line starting with
  `:` (indented at most three spaces) followed by a space or tab and content. A blank line may separate the terms and
  the definition. Each line of the paragraph before the first marker is one term (a soft or hard line break separates
  terms); consecutive `:` lines are further
  definitions of the same terms. Definition content continues on lines indented to the content column (as for list
  items, `:   ` gives four columns), and paragraphs accept CommonMark lazy continuation lines. Separate entries with
  a blank line.
- Each term becomes a `ListItem` with `definition_list_role: "term"` and the usual heading parent. A definition's first
  element is a `ListItem` (when it is a paragraph) with `definition_list_role: "definition"`, `definition_term_ids`
  (every term of the entry) and the first term as `parent_id`. Later blocks of the definition take that element as
  `parent_id`. A definition that starts with a list has no definition element: its items, like the definition's later
  blocks, take the first term as `parent_id`. When a note or definition starts with another construct whose first
  element also carries roles (a note starting with a definition list, for example), that element carries both sets of
  metadata. A heading inside a definition outlines only that definition: later terms and text after the list keep the
  outer heading parent. `category_depth` follows list nesting: terms at the list depth, definition content one deeper.
- A marker without a preceding paragraph or definition (at the start of a container, after a heading or code block),
  a marker without content and an indented code line stay literal. Nested definitions count toward `maxNesting`.
- Upstream with `def_list` makes the term `UncategorizedText` and the definition a `ListItem`; texts agree.

### Front matter

- Only a block on the first line is front matter: `---` (spaces may follow), then content, then a closing `---` or
  `...` line within the first 4,096 characters of content. Its first content line must be a top-level `key:` line or
  a comment, and it must contain at least one `key:` line (or be empty). A byte-order mark is ignored. Otherwise (no
  closing line in time, another first line, or only comments) the input is not front matter and is parsed exactly as
  with the option off, so a leading thematic break, and a `# Heading` between two of them, keep their meaning.
- The content is read as a data-only YAML subset: top-level `key: value` pairs with plain keys; plain, single-quoted and
  double-quoted scalars; block sequences (`- item`) and one-line flow sequences (`[a, "b"]`) of scalars; literal (`|`)
  and folded (`>`) block scalars with `-`/`+` chomping; comments and blank lines. Every value is a string or a list of
  strings: `3`, `true` and `2026-11-08` are not typed, and an empty value is `""`. A comment starts at a `#` preceded
  by a space or tab; `a#b` is a value. Nothing is resolved, instantiated or executed.
- Well-formed values are added to every element as the `front_matter` object, in source order. The block itself emits
  no element and does not affect headings. Chunking keeps `front_matter` on combined chunks (first value).
- Tags (`!tag`, `!!python/...`), anchors and aliases, directives, nested or flow mappings, multi-line plain or quoted
  scalars, tab indentation, duplicate keys, values starting with sequence or mapping syntax (`- x`, `? x`), invalid or
  surrogate `\u` escapes and more than 64 keys and sequence items together make the block malformed. The limits are
  small because the values are repeated on every element. A malformed block is emitted unchanged (without delimiters, line endings normalized to `\n`) as one `UncategorizedText` element before the
  body, no values are added, and the document gets one `MARKDOWN_FRONT_MATTER_INVALID` warning whose message names the
  rule but not the content.
- Upstream with `meta` drops the block and exposes no values through `partition_md`; texts agree.

## Qualification evidence (#57, #58)

The reference pin is kept separate from the historical `347fbbfd` markup references in
`docweave-integration-tests/src/test/resources/markup-native-reference.json`, which are unchanged.

- Upstream: Unstructured **0.27.37** from PyPI (wheel SHA-256 `97945ceb…1393`, installed RECORD SHA-256
  `18d3ee36…2996`; before importing upstream the generator also re-hashes every installed `unstructured/` file
  listed in RECORD and refuses any missing or changed file), Python 3.13.18 in a `python:3.13-slim` container, Markdown 3.11.3, beautifulsoup4 4.15.0, lxml 6.1.3,
  `en_core_web_sm` 3.8.0 (wheel hash verified). The full dependency list is in the reference file.
- Options: public `partition_md(text=…, metadata_filename=…, languages=["eng"], html_parser_version="v1",
  extensions=["tables", "fenced_code"])` (the documented default extensions). `extensions.md` and `front-matter.md`
  also run with `["tables", "fenced_code", "footnotes", "def_list", "meta"]` to show how upstream handles the deferred
  syntax.
- Corpus: eight owned documents in `benchmarks/fixtures/markdown/` (README, changelog, guide, edge cases, inline HTML,
  block HTML, extension syntax, front matter), written for this repository and licensed Apache-2.0. Hashes are in
  `manifest.json`.
- Output: `docweave-integration-tests/src/test/resources/markdown-qualification-reference.json`, checked offline by
  `MarkdownQualificationReferenceTest` (no Python or network needed).

Regenerate the reference (review the diff before replacing the checked-in file):

```sh
docker run --rm -v "$PWD:/repo:ro" -v /tmp/out:/out -w /repo <image with unstructured[md]==0.27.37 and en_core_web_sm-3.8.0> \
  python benchmarks/tools/generate_markdown_qualification_references.py \
  --model-wheel /wheels/en_core_web_sm-3.8.0-py3-none-any.whl --output /out/markdown-qualification-reference.json
```

### Agreements

- Changelog: element text, types, heading hierarchy, link labels/URLs and emphasis contents/tags are identical.
- Guide, README, edge cases: all prose, heading, list and table-cell text agrees, apart from the differences below.
  Unicode (CJK, Arabic, Greek, combining marks, ZWJ emoji, supplementary-plane letters) and entities agree.
- Emphasis contents and tags agree wherever both sides segment a span the same way.
- Ragged GFM rows: both drop the surplus cell.
- Default extension syntax: text agrees exactly; both keep task markers, tildes, bare URLs and definition lists
  literal. With `footnotes`, `def_list` and `meta` upstream, DocWeave `EXTRACT_DOM` with the matching options gives the
  same footnote, definition and front-matter texts.

### Dialect and policy differences (not defects)

| Observation | DocWeave | Unstructured 0.27.37 |
| --- | --- | --- |
| Fenced code inside a list item | `CodeSnippet` with language, parented to the item | Merged into prose (`sh tar …`) |
| Lazy continuation after list code | Joins the list item | Merged into one paragraph with the code |
| Backslash hard break | Line break, backslash removed | Backslash kept |
| Paragraph of linked badge images | One `NarrativeText`, all image URLs and links kept | One `Image` per badge |
| Inline reference image | Alt text stays in the paragraph | Paragraph split around an `Image` |
| Image-only heading | `Title` with `image_urls` | `Image` |
| Code-span pipe in a table header | Not a table (GFM cell-count rule) | Table |
| Code block trailing newline | Kept, with `code_info`/`code_language` | Trimmed, no code metadata |
| List `category_depth` | Starts at 0, nested lists increase | 1 for every level in these runs |
| Prose classification | Heuristic `NarrativeText` | NLP-based, often `UncategorizedText` |
| `<details>` content | Kept in `EXTRACT_DOM` and in the Markdown after a blank line | Dropped by Python-Markdown |
| `hidden` inline content | Removed in `EXTRACT_DOM` | Kept |
| Emphasis segmentation | Maximal runs, with offsets, including table cells | One entry per HTML text segment, none for tables |
| Front matter (option off) | Level-2 `Title` | `UncategorizedText` (default) or removed (`meta`) |

### Defect fixed

- **List-item continuation blocks lost their item.** In AST modes, a list item's later paragraphs or code blocks (in a
  loose list, or after a fenced block) took the heading as parent instead of the item's first element, so they read as
  separate items. They now take that element as `parent_id`, as nested lists already did. Text, type and order are
  unchanged. `EXTRACT_DOM` and the HTML format keep their existing behavior (the HTML strategy treats each block in an
  `<li>` as a sibling); aligning them would change HTML output and is a separate decision.

### Observed limitations

- CSS-based visibility is not evaluated in `EXTRACT_DOM`. `SKIP` keeps the text of hidden inline spans and drops the
  whole text of raw blocks.
- Surplus ragged-row cells are dropped silently, as GFM specifies. No warning is reported.
- A link with an empty label (`[](url)`) produces no link metadata.
- Footnote and definition-list structure metadata is element-level; like `parent_id`, it is not kept on combined
  chunks (original elements keep it when `includeOriginalElements` is set).
- Eight owned documents and focused tests do not establish real-world parity. Broader permitted real-document samples
  and HTML v2 references remain future work.
