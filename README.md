# DocWeave

<p align="center">
  <img src="docs/assets/brand/docweave-logo.png" alt="DocWeave logo" width="520">
</p>

A JVM library for turning documents into ordered semantic elements and retrieval-ready chunks.

Status: Java 21+ library with native TXT/HTML/Markdown/CSV/TSV/XML/DOCX partitioning and an initial native PDF module, compatibility JSON, basic/title-aware chunking, and bounded virtual-thread document batches.

## Why DocWeave?

**DocWeave** combines **document** and **weave**. Documents contain more than plain text:
headings, paragraphs, tables, images and metadata all contribute to their meaning. The name
reflects the library's purpose: bringing document content, structure and context together
as structured elements and useful chunks that applications can work with.

## Getting started

Use **Java 21 or later**. For a Maven application, add the PDF-only beta dependency
(no additional repository configuration is needed):

```xml
<dependency>
    <groupId>io.docweave</groupId>
    <artifactId>docweave-pdf</artifactId>
    <version>0.1.0-beta.6</version>
</dependency>
```

Save this example as `ExtractPdf.java` in your application's source directory, then run
its `main` method with a PDF path as the first argument. The dependency supplies core APIs
and PDFBox transitively; the example uses only the public `DocWeave` interface.

```java
import dev.docweave.DocWeave;

import java.nio.file.Path;

/** Extracts ordered elements and reports nonfatal PDF diagnostics. */
public final class ExtractPdf {
    private ExtractPdf() {
    }

    /**
     * Reads the PDF supplied as the first command-line argument.
     * @param args exactly one PDF path
     * @throws Exception if the file cannot be read or partitioned
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: ExtractPdf <report.pdf>");
        }
        final var result = DocWeave.create().partition(Path.of(args[0]));
        result.elements().forEach(element ->
                System.out.printf("%s: %s%n", element.type(), element.text()));
        result.warnings().forEach(warning -> System.err.println(warning));
    }
}
```

Native extraction reads embedded text; scanned pages need the separately installed,
explicitly configured [Tesseract OCR adapter](docs/ocr-tesseract.md). See
[PDF support](docs/pdf-support.md) for ruled tables, image placements, optional image bytes,
layout options and remaining limitations. Table recognition and reading order have documented
boundaries; this beta does not promise full layout fidelity for every PDF.

See the [version-pinned PDF comparison](docs/pdf-comparison.md) for DocWeave/Unstructured
capabilities, scoped measurements and remaining acceptance gaps.

For Spring Boot, see the complete [PDF extraction and chunking example](docs/spring-boot.md),
including native and OCR-enabled bean configuration, mixed-page and scanned-table
policies, constructor injection and upload limits.

For other formats, select the corresponding [Maven module](docs/modules.md). Use artifact
`docweave` at the same version for all formats; optional OCR and ONNX adapters remain separate.
JSON serialization, chunking and batches also have their own modules. Java imports remain
`dev.docweave` even though the Maven group is `io.docweave`.

The source checkout currently uses `0.1.0-SNAPSHOT` for development. To build it locally,
use Maven 3.9+ and run `mvn verify`; the beta dependency above is for consuming the release.

## Initial milestone

- [Release notes and version-tag checklist](docs/releases/README.md)
- [Draft PDF beta scope and release checklist](docs/pdf-beta-checklist.md)
- [Compatibility specification](docs/compatibility.md)
- [Product roadmap](docs/product-roadmap.md)
- [Detailed implementation backlog](docs/roadmap.md)
- [Benchmark workflow](benchmarks/README.md)
- Deeply immutable Java element model in `docweave-core/src/main/java/dev/docweave/core`.
- Jackson 3 JSON codec, including zlib/base64 original-element provenance.
- Native DOCX comparisons on two owned fixtures plus two published reports, with explicit differences and review limits in [the comparison report](docs/docx-comparison.md).
- Native HTML/Markdown references covering structured content and raw-HTML policies; see [measured comparisons](docs/markup-comparison.md).
- Native TXT/CSV/TSV references for grouping, size and header policies; see [text comparisons](docs/text-comparison.md).
- Native XML references for ordering, namespace metadata and explicit option/entity boundaries; see [XML comparisons](docs/xml-comparison.md).
- Unicode-aware chunking checked against 41 pinned upstream comparisons on Python 3.13: 32 exact normalized matches and nine documented differences.
- Input-order file batches with bounded outstanding virtual-thread tasks and cooperative cancellation.
- TXT paragraph grouping and HTML headings, lists, tables, links and code blocks.
- CommonMark AST extraction, pipe tables, image references and explicit raw-HTML policies.
- Quoted CSV/TSV records with preserved strings, configurable headers and table budgets.
- Secure streaming XML with ordered text runs, namespace-aware paths and configurable limits.
- Opt-in scanned complete ruled tables with bounded cell OCR, escaped HTML and exclusive table/text ownership; see [PDF scope](docs/pdf-support.md).
- Explicit optional [Tesseract OCR](docs/ocr-tesseract.md) for PDF textless pages and opt-in masked image regions on mixed pages, with managed process limits, languages and timeouts; installed separately from default parsers. Unreleased [strategy presets](docs/pdf-support.md#convenience-strategy-presets-unreleased) add FAST/AUTO/HI_RES/OCR_ONLY routing alongside existing APIs.
- Native quarter-turn PDF text/opt-in paragraphs and fixed two-column order, multiple ruled tables with rectangular merged cells, opt-in aligned numeric tables and raster-image placements/optional PNG bytes; see [scope and remaining PDF milestones](docs/pdf-support.md).
- DOCX body order, heading/list hierarchy, hyperlinks and tables using Apache POI.
- Typed options/results/errors, encoding and format detection, input/element/depth limits.
- Interface-based services and builders with package-private implementations.
- Runtime partitioner strategies selected through an injectable registry.
- Extensive public Javadocs checked with doclint and warnings treated as errors during `mvn verify`.

Requires Java 21 or later and Maven 3.9+. This is a plain Java library; Spring Boot is not a dependency. The development reactor uses `io.docweave:docweave:0.1.0-SNAPSHOT`; application consumers can use the `0.1.0-beta.6` dependency above. Java packages remain `dev.docweave`; package migration is deferred. Development validation is moving into the Java/JUnit [validation module](docweave-validation/README.md); remaining Python benchmark/reference helpers are documented there. Neither the production library nor Maven Java tests require Python. The released upstream reference pin and reviewed main drift are tracked in [upstream status](benchmarks/upstream-status.json).

Build and test:

```sh
mvn verify
```

Benchmark scorers run as JUnit tests in `mvn verify`; the remaining Python checks guard only the
upstream reference generators:

```sh
python3 -m unittest discover -s benchmarks/tools -p 'test_*.py'
```

The Maven groupId is `io.docweave`. DocWeave code is licensed under [Apache License 2.0](LICENSE), except separately identified third-party material, which retains its notices and terms. The `io.docweave` namespace is registered in Central Portal; see [registration details](docs/central-registration.md). PDF beta.6 is published on Maven Central, as confirmed by its [official artifact metadata](https://repo.maven.apache.org/maven2/io/docweave/docweave-pdf/maven-metadata.xml). Development-only additions remain in the Unreleased changelog until a later publication.

## Maven modules

Use `io.docweave:docweave-docx` for DOCX only, or select `docweave-txt`, `docweave-html`,
`docweave-markdown`, `docweave-csv`, `docweave-tsv`, `docweave-xml` or `docweave-pdf`. All depend on the
JDK-only `docweave-core`; parser dependencies remain with the corresponding modules.
JSON, chunking and batch services are separate opt-in modules. The `io.docweave:docweave`
artifact remains the all-formats convenience dependency. See the [module map and usage](docs/modules.md).
Java packages stay `dev.docweave`.

## Java API

```java
import dev.docweave.DocWeave;
import dev.docweave.chunking.Chunker;
import dev.docweave.chunking.ChunkOptions;
import dev.docweave.json.ElementJson;
import java.nio.file.Path;

DocWeave processor = DocWeave.create();
ElementJson json = ElementJson.create();
var result = processor.partition(Path.of("report.html"));
var options = ChunkOptions.builder()
    .maxCharacters(1500)
    .newAfterCharacters(1000)
    .combineTextUnderCharacters(0) // preserve title boundaries
    .build();
var chunks = Chunker.create().byTitle(result.elements(), options);
json.write(Path.of("chunks.json"), chunks);
```

Optional snapshot [contextual chunk enrichment](docs/contextual-chunking.md) adds caller-generated
document context after either chunk strategy, keeping original text and provenance separate.
It requires an explicitly supplied provider; default chunking makes no model calls.

Service contracts live in `dev.docweave`, `dev.docweave.json`, `dev.docweave.chunking`, and `dev.docweave.batch`. Service factory implementations and parser strategies are package-private; records and enums are public immutable value types. Small public JDK discovery bridges implement the module SPI. For raw-document batches:

```java
var results = DocumentBatch.create().partitionAndChunk(
    inputPaths, PartitionOptions.defaults(), Chunker.Strategy.BY_TITLE, options, 4);
```

Each active file task runs on a virtual thread. Chunking stays synchronous within it. The batch runner caps outstanding tasks, returns input-order results, and cancels outstanding tasks on failure/interruption. Operations must cooperate with interruption. Optional task/batch deadlines are configured through `BatchOptions`; existing integer-window calls retain their original behavior.

Use `ElementFileBatch.create().chunk(...)` for saved JSON element arrays. Interfaces can be injected or implemented by alternative processors. See [API conventions](docs/api-design.md).

## Saved-element JSON read limits

`ElementJson.create()` and `ElementFileBatch.create()` now use bounded reads by default:
16 MiB of UTF-8 input, 100,000 element objects, JSON container depth 128, and a decoded string
limit of 16 Mi UTF-16 code units. These limits are independent of `PartitionOptions`.
Configure another immutable policy when needed:

```java
var readOptions = new dev.docweave.json.JsonReadOptions(
    32 * 1024 * 1024, 200_000, 128, 16 * 1024 * 1024);
var codec = ElementJson.create(readOptions);
var batch = ElementFileBatch.create(readOptions);
```

The batch policy applies to list-returning, deadline-enabled and incremental execution.
Files are size-checked before reading and checked again against bytes actually consumed, including
one extra byte to detect overflow. UTF-8 decoding is strict. String input counts its encoded UTF-8
bytes without allocating another encoded copy and rejects unpaired source surrogates. The reader
materializes one element object at a time before assembling the final immutable list. Limits do
not impose a heap budget; input bytes/text, individual objects and final results still materialize.
Jackson retains additional number/name constraints. Writes are unbounded, so writing a file does
not guarantee that the default read policy will accept it; choose suitable explicit read limits.
Provenance retains its separate 16 MiB decoded-data cap and is not governed by these custom limits.

Byte/count violations throw `IllegalArgumentException`; parser depth/string constraints throw a
Jackson exception. No partial list is returned. File interruption raises `InterruptedIOException`,
while string decoding raises `CancellationException`; both preserve the interrupt flag. Checks
run during byte reads, string byte counting and between elements. An individual parser operation
or immutable metadata snapshot can still delay cancellation. Batch APIs wrap worker failures in
`ExecutionException`, subject to their documented deadline race behavior.

## Incremental batch delivery

Use `forEachOrdered` to avoid retaining every input and result:

```java
try (var paths = java.nio.file.Files.list(inputDirectory)) {
    DocumentBatch.create().partitionAndChunkEach(paths::iterator,
        PartitionOptions.defaults(), Chunker.Strategy.BY_TITLE, ChunkOptions.defaults(),
        new BatchOptions(4), result -> save(result));
}
```

`save` is your application callback. Generic processing uses
`BatchProcessor.forEachOrdered(inputs, batchOptions, operation, consumer)`; saved element JSON uses
`ElementFileBatch.chunkEach(...)`. Inputs are read lazily on the calling thread, without a full
snapshot. Results arrive sequentially in input order on that thread. At most `maxInFlight`
tasks/results are outstanding; the next input is read only after a result has been consumed.
The caller owns input resources and must keep them open until the call returns. Retaining outputs
in the callback defeats the memory benefit; each file's partition/chunk lists still materialize.

Already delivered results remain delivered if a later input, worker or callback fails. Worker and
callback failures are wrapped in `ExecutionException`; callback interruption propagates as
`InterruptedException`. Iterator failures propagate directly. A later worker failure may stop delivery
before an earlier successful result is delivered. All incremental calls cancel outstanding work and
request shutdown without waiting indefinitely; interruption-ignoring workers may outlive return.
Task deadlines exclude callback time; overall deadlines include iteration and callbacks. Blocking
iteration/callbacks run on the caller and cannot be stopped by the timer; expiry is checked when
they return. Legacy custom implementations explicitly reject incremental delivery unless overridden.
Existing list-returning methods keep their contracts.

## Cooperative interruption

Built-in chunking checks interruption at entry, element staging/combining, text normalization and
splitting, metadata consolidation, structural table traversal/fragmentation, and before returning.
It throws `CancellationException` without clearing the interrupt flag or returning a partial list.
Batch calls expose worker cancellation as an `ExecutionException` cause; a task deadline can instead
win the race and expose `BatchTimeoutException`. Previously delivered incremental results remain delivered.

Native partitioning retains `InterruptedIOException` at checkpoints, preserving the interrupt flag.
Checks also surround source loading and parser boundaries, DOCX inline-text traversal and media
metadata traversal. Shared Unicode normalization, blank/letter/control scans and TXT code-point
sizing now poll during long inputs. TXT scans line boundaries lazily, retaining all previous Unicode
line-break and CRLF behavior without allocating a complete line array first. These scans check at
entry/completion and periodically during traversal. Direct synchronous calls honor interruption but
have no automatic deadline.
Checks cannot stop an individual library parser/renderer, codec, regex, bulk copy or blocking I/O call
while it is executing. Cancellation latency is not guaranteed; custom strategies must cooperate too.

## Batch execution deadlines

```java
var batchPolicy = new BatchOptions(4, Duration.ofSeconds(30), Duration.ofMinutes(5));
var results = DocumentBatch.create().partitionAndChunk(
    inputPaths, PartitionOptions.defaults(), Chunker.Strategy.BY_TITLE, options, batchPolicy);
```

Import `dev.docweave.batch.BatchOptions` and `java.time.Duration`. The task budget starts when
its virtual-thread worker begins and covers file loading, decoding, partitioning and chunking
together. The overall budget starts after argument validation/input snapshotting and includes
submission and input-order result collection. `Duration.ZERO` disables either budget;
`new BatchOptions(4)` disables both. Negative or nanosecond-overflowing durations are rejected.

With a deadline enabled, worker failures/timeouts are observed independently of result order.
The call throws `ExecutionException`; a timeout cause is `BatchTimeoutException`, exposing
`scope()`, `inputIndex()` (zero-based for TASK, -1 for BATCH), and the configured `timeout()`.
Outstanding tasks are interrupted, executor shutdown is requested, and no partial results are
returned. The timed path does not wait indefinitely for cleanup. Custom operations or parser
phases ignoring interruption may continue after return; there is no forced termination, real-time
return guarantee or rollback of side effects. Tasks must be safe for cooperative cancellation.

The same policy overload exists on `BatchProcessor.mapOrdered(...)` and
`ElementFileBatch.chunk(...)`. Successful results remain immutable and in input order, and at most
the configured window is submitted but not collected. Original integer-window overloads and
zero-deadline policies keep input-order failure observation and wait for worker termination during
cleanup. Existing custom service implementations can use zero-deadline policies; enabled deadlines
are rejected unless they override the new overload, so timeouts cannot silently go unenforced.
Single synchronous `DocWeave.partition(...)` calls do not acquire a deadline automatically.

## Table isolation policy

Tables occupy separate chunks by default. To allow small tables to share text chunks:

```java
var options = ChunkOptions.builder()
    .isolateTable(false)
    .maxCharacters(1500)
    .build();
```

Staging and by-title small-section combining then treat tables as ordinary text for grouping.
Mixed groups (including multiple tables) emit `CompositeElement` using normalized stored table
text and the usual blank-line element separator. Size, heading/page staging and combining limits
still apply. Mixed chunk metadata excludes table HTML, cell geometry and extraction-method fields;
original source elements retain these in `orig_elements` when provenance is enabled. With
provenance disabled, this structure is omitted from mixed output. Keep isolation enabled when
structural table fidelity is required.

A table that remains alone retains the existing structural Table/TableChunk path, including HTML
budgets and optional header repetition. `skipTableChunking(true)` requires isolation; conflicting
options fail at build time. Overlap never crosses table-bearing groups, including mixed groups.
This conservative overlap rule and omission of table-only HTML from mixed metadata intentionally
differ from upstream's opt-out behavior; full opt-out parity is not claimed.

## Custom chunk separators

```java
var options = ChunkOptions.builder()
    .maxCharacters(1500)
    .textSplittingSeparators(List.of("; ", ". ", " "))
    .build();
```

Separators are ordered **literal** preferences for oversized narrative, code and text-only table
splitting. The first usable separator wins; its last eligible occurrence defines the boundary.
Visible delimiters stay at the end of the fragment and count against the maximum; whitespace-only
delimiters and boundary whitespace are trimmed. Existing overlap rules apply. The default remains
newline then space, followed by code-point fallback. An empty list forces code-point splitting;
empty strings, null entries and unpaired surrogates are rejected. Options snapshot the list.

This does not change the blank lines joining whole elements or narrative whitespace normalization:
newlines/tabs inside prose may already have normalized to spaces. Code retains its formatting.
Structural HTML tables continue to split by rows/cells/escaped text. These are literal separator
semantics, not regex matching or full upstream custom-separator parity.

## TXT paragraph and size policies

TXT defaults to blank-line grouping with wrapped lines joined and simple classification enabled.
Use `LINE_BREAKS` for one element per nonblank line, or opt into automatic grouping and size limits:

```java
var textOptions = PartitionOptions.builder()
    .paragraphMode(PartitionOptions.ParagraphMode.AUTO)
    .txtOptions(new TxtOptions(80, 1500))
    .build();
var result = processor.partition(DocumentSource.text(text, "report.txt"), textOptions);
```

`AUTO` separates short title-case/all-capital lines (at most eight words and 80 code points),
blank lines and list items. It joins soft wraps and indented list continuations. A completed
sentence followed by an unindented uppercase/title-case or uncased-letter line starts a new
paragraph. These rules do not use NLP and can mistake abbreviations or title-like prose for
boundaries; use explicit grouping when document structure is known.

`TxtOptions` measures normalized text in Unicode code points. The maximum is hard and prefers
word boundaries, splitting unbroken text at code-point boundaries. Categories remain those of the
source paragraph. The minimum is a best-effort target: merge a pending short prose fragment with
an entire following prose paragraph of the same output category only when the combination fits.
It may cross blank/line boundaries, but titles and lists remain barriers even with classification
disabled. Short remainders are retained. Defaults are zero minimum and `Integer.MAX_VALUE`
maximum, preserving existing output. Sizing applies only to TXT; it is separate from chunking.

## Runtime partitioner strategies

Every format strategy implements the public `Partitioner` contract. Native orchestration detects/decodes the source and then asks `PartitionerRegistry` for the matching strategy; it contains no parser-instantiation switch. Registry builders start with all seven implemented formats and replace a format when another strategy is registered:

```java
var registry = PartitionerRegistry.builder()
    .register(customHtmlPartitioner) // implements Partitioner; format() returns HTML
    .build();
DocWeave processor = DocWeave.create(registry);
```

Implement `partition(PartitionContext context)` and emit semantic elements with `context.emit(...)`. The context supplies stable IDs, source metadata, warnings, output limits and interruption checks. It becomes unwritable after the strategy returns. Strategies must be thread-safe; the built-in HTML strategy creates a document-local walker on every call.

Use `PartitionerRegistry.of(...)` to configure only specified strategies. Missing formats fail explicitly. The format model/detector covers TXT, HTML, Markdown, CSV, TSV, XML and DOCX; registration replaces supported strategies rather than adding arbitrary new MIME types. Inject a custom processor into batches using `DocumentBatch.create(processor, Chunker.create())`.

## Format-specific policies

Markdown and CSV/TSV use filename hints (`.md`, `.markdown`, `.csv`, `.tsv`) or an explicit `format(...)`. Anonymous text is not guessed as delimited data or Markdown. Structured-format filename hints take precedence over HTML-looking cells/raw markup.

```java
var limits = new TableLimits(10_000, 200, 200_000, 100_000);
var options = PartitionOptions.builder()
    .format(DocumentFormat.CSV)
    .delimitedOptions(new DelimitedOptions(
        DelimitedOptions.Header.NONE, true, limits))
    .build();
var result = processor.partition(DocumentSource.of(Path.of("data.csv")), options);
```

Use `markdownOptions(new MarkdownOptions(true, 128, MarkdownOptions.RawHtml.SKIP, limits))` to configure pipe tables, AST depth and raw-HTML handling. Raw HTML defaults to literal text with a diagnostic. Select `MarkdownOptions.RawHtml.EXTRACT_DOM` to render the complete Markdown document and extract its combined HTML DOM. This mode shares heading/list hierarchy across Markdown and HTML, resolves links, filters hidden/script content and applies HTML depth/header/footer policies. Table budgets apply to pipe tables and embedded HTML tables (including nested tables); spans count source cells rather than expanded grid positions. Fenced/indented code retains its CommonMark literal whitespace. Images retain references and alt text without fetching assets.

Delimited files emit one `Table` with tab/newline display text and escaped cell HTML. The first record is a header by default; `Header.NONE` retains every record as data. Values stay strings, including whitespace and leading zeros. Quoted delimiters/newlines, doubled quotes and empty/trailing cells are preserved. Uneven rows fail unless `strictColumns` is false; permissive handling retains row widths and adds one diagnostic. Structured HTML is authoritative for cell boundaries when values contain tabs/newlines.

## XML extraction

XML uses the existing `Partitioner` strategy contract; the implementation and document-local streaming state are hidden. `XmlOptions` is an immutable value record. Source loading, runtime dispatch and shared emission remain separate responsibilities.

```java
var options = PartitionOptions.builder()
    .xmlOptions(new XmlOptions(128, 50_000, false, true))
    .build();
var result = processor.partition(DocumentSource.of(Path.of("report.xml")), options);
```

A `.xml` filename, XML declaration, or explicit `format(DocumentFormat.XML)` selects XML. Anonymous declaration-free markup is ambiguous and needs an explicit format. Byte sources support XML declaration encodings and UTF BOM/signatures; explicit charset and BOM policies take precedence. Already-decoded text ignores declaration encoding.

Generic XML emits nonblank `UncategorizedText` runs in source order without duplicating descendant text. For `<p>before<b>inside</b>after</p>`, the output is `before`, `inside`, `after`. Each run includes `xml_tag`, root-to-owner `xml_path` and `category_depth`; optional `xml_attributes` retains string values. Namespace-qualified names use `{URI}local`, independently of source prefixes. Paths identify ancestry, not unique sibling positions. No universal title/table semantics are inferred.

Text normalizes whitespace by default. `preserveWhitespace` retains it for all nonblank runs; inherited `xml:space="preserve"` also retains it, and `xml:space="default"` resets inheritance. Whitespace-only runs, comments and processing instructions are omitted; CDATA and predefined/numeric entities remain text. DTDs and custom entities fail explicitly; schemas, stylesheets and XInclude resources are never fetched. XML 1.0/1.1 handling follows the JDK reader. Namespace paths and attributes are extension metadata; composites retain them through original-element provenance rather than copying them to consolidated chunk metadata. XML extraction parity with upstream has not been established.

## DOCX extraction

```java
var result = DocWeave.create().partition(Path.of("report.docx"));
var options = PartitionOptions.builder()
    .docxOptions(new DocxOptions(2_000, 64L * 1024 * 1024, 16 * 1024 * 1024,
        256, 1_000_000, true, TableLimits.defaults()))
    .build();
var withStoredHeaders = DocWeave.create().partition(
    DocumentSource.of(Path.of("report.docx")), options);
```

DOCX accepts files and byte snapshots. A `.docx` filename or explicit DOCX format routes directly; anonymous ZIP bytes are attempted as DOCX and must contain the supported OOXML Word package structure. Ordinary ZIP, PPTX/XLSX, encrypted Word packages, binary `.doc`, and macro-enabled packages fail explicitly. DOCX diagnostics report `encoding="binary"`; text charset policies do not decode the ZIP.

Body paragraphs, tables and block/row/cell content controls retain their stored order. Heading outline levels and conventional Heading1–Heading9/Title styles produce Titles; inherited styles are resolved with cycle protection. Direct outline level 9 keeps a paragraph as body text, except in table cells with an active conditional heading as described below. Numbering properties identify ListItems and nested parent anchors. Common list formats, level starts/overrides and restart counters produce `docx_list_label` metadata without prefixing semantic text; table-cell lists retain labels/offsets in `docx_list_items`. Other paragraphs are NarrativeText. Run whitespace is normalized, and hyperlinks retain displayed text, target and Unicode code-point offsets. `docx_style` and `docx_num_id` are extension metadata.

Tables retain row/cell order, empty values, paragraph breaks, repeated-header flags, horizontal grid spans and empty vertical continuations. HTML preserves cell boundaries, `colspan` and `rowspan`; display text includes empty logical positions for spans/continuations. Nested tables flatten into cell text with a diagnostic while preserving links, references and supplemental anchors. Structured cells and omitted grid positions are retained. Legacy horizontal merges and nonempty vertical continuations append their content to the origin cell, preserving links and spans; `docx_merge_content_policy=append_to_origin` documents that choice. Unmatched merge continuations fail explicitly. HTML-backed tables now use shared structural chunking.

Headers/footers are excluded by default. When enabled, stored headers appear once before the body and stored footers once after it, in POI part-list order, with Header/Footer categories. Tables in these parts remain Table elements with `docx_part` metadata. These are stored parts, not per-page repeats. No rendered page numbers, coordinates or visual reading order are invented. Normal footnotes/endnotes and comments are emitted after the body, with source reference IDs and comment authors; separator notes are excluded. Text-box paragraphs/tables and image references are emitted after their anchor paragraph/table, with parent IDs and normalized code-point offsets. Images can optionally include bounded base64 payloads. Revision selection and cached field results are configurable/documented below. Rendering, OCR, unsupported drawing geometry and full upstream DOCX parity remain outside current coverage.

Preflight bounds every ZIP entry, total expansion and every XML part identified by extension/content type before POI constructs its document model. Defaults: 2,000 entries, 64 MiB expanded total, 16 MiB per entry, XML depth 256 and 1,000,000 XML element starts across parts. DTD/custom entities and macros are rejected, no external relationship target is fetched, and no archive entries are extracted to disk. The archive and POI model are held in memory; these budgets are not heap limits or deadlines. POI retains its own ZIP protections; DocWeave does not mutate their process-wide settings.

Supplemental content uses immutable typed policies:

```java
var content = new DocxContentOptions(
    true, true, true, // notes, comments, text boxes
    DocxContentOptions.Images.EMBED, 4 * 1024 * 1024,
    DocxContentOptions.Revisions.ACCEPTED);
var options = PartitionOptions.builder()
    .docxOptions(DocxOptions.defaults().withContentOptions(content)).build();
```

Defaults include notes, comments and text boxes, image references, and accepted revisions. `Images.SKIP` excludes images; `REFERENCE` retains internal package paths or external URLs; `EMBED` additionally includes bounded base64 bytes and SHA-256 for embedded images. External references are never fetched, vectors are not executed, and image pixels are not decoded. Embedding over budget fails with `IMAGE_LIMIT`.

`ACCEPTED` includes insertions/move destinations and excludes deletions/move origins; `ORIGINAL` reverses that selection; `ALL` retains both alternatives in stored XML order. Inline revision ranges/author metadata and note/comment reference offsets are retained. Table-row insertion/deletion markers are also selected. This selects stored content, without modifying the document or reproducing Word's full layout/revision engine.

Fields expose cached display results and `docx_field_instructions`; instructions are never executed or fetched. Simple/complex HYPERLINK fields retain their cached label and stored URL, including instructions split across runs. Numbering supports decimal/zero-padded decimal, Roman (1–3999), alphabetic, English ordinal, bullet and none formats. Unsupported numbering formats retain their source format/template/counter with a diagnostic, rather than inventing labels. Counters are local to each stored part and continue through its table-cell lists. Custom font glyph mapping, picture bullets and the full international Word numbering catalog remain limitations. Font-based `w:sym` symbols and selected `w:altChunk` imports emit one `DOCX_CONTENT_OMITTED` warning per occurrence. Glyphs are not guessed and import relationships are not resolved; surrounding text is preserved. Skipped revisions and disabled supplemental parts do not produce these omission warnings.

Numbering definitions follow `numStyleLink` through numbering-style `numId` references and `basedOn`
chains, applying inner then outer instance overrides. Direct single-level decimal instances with no
style links or instance overrides share a counter when they reference the same abstract definition.
An explicit override or a distinct abstract definition separates sequences. Multilevel lists, other
formats and style-linked aliases retain instance-local counters. Source `numId` metadata is preserved. A level's `pStyle` selects the nearest matching paragraph style in its
inheritance chain when no direct paragraph level exists. Direct paragraph levels win; unmatched styles
retain the existing inherited-level/default-zero fallback. Numbered headings remain Titles and now
retain numbering labels/counters. Table-cell lists share the stored part's counters.

Missing, wrong-type, cyclic or overlong numbering-style links preserve semantic text and source IDs,
omit invented labels, and emit `docx_numbering_unresolved` plus a `DOCX_CONTENT_OMITTED` warning.
Both numbering-reference and numbering-style inheritance traversal stop at 128 hops and check
interruption. `styleLink` is descriptive and does not redirect numbering. These are stored numbering
rules, not Word layout/rendering parity; complex conditional style/override conflicts remain outside the current evidence. See the
[OOXML numbering-style reference](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.numberingstylelink?view=openxml-3.0.1),
[paragraph-style level association](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.paragraphstyleidinlevel?view=openxml-3.0.1)
and [Word's inherited-level deviation](https://learn.microsoft.com/en-us/openspecs/office_standards/ms-oe376/02932249-a32e-48ac-8237-6f91b4182403).

Paragraphs with no explicit `pStyle` now use the declared default paragraph style, including its
`basedOn` chain, outline properties, numbering and level associations. Explicit style references
(including missing references) do not fall back to this selection. Direct numbering/levels and
`numId=0` suppression retain precedence. No default is guessed from the name `Normal`, and defaults
of character/table/numbering types do not apply. True/on/1 enable the stored default flag; false/off/0
and absent flags do not. Multiple flagged paragraph defaults use the last declaration in stored order
as a deterministic recovery policy. Default selection is cached per document-local resolver.

`docx_style` records the selected effective style; `docx_style_source="default"` marks implicit
selection. Table-list entries carry the same information in `docx_list_items`; source text remains
unchanged. JSON and chunk provenance preserve these fields. This selects a named default paragraph
style, not every Word formatting layer: full conditional formatting and complete
layout/style parity remain outside coverage. See Microsoft's
[default-style retrieval example](https://learn.microsoft.com/en-us/dotnet/standard/linq/retrieve-paragraphs-styles).

`docDefaults/pPrDefault/pPr` now supplies the semantic base properties `outlineLvl`, `numId` and
`ilvl` when no higher-precedence value is available. This applies beneath explicit or implicit named
styles, including paragraphs whose explicit style reference is missing. Numbering properties merge
individually: a style can supply the instance while retaining a document-default level. Direct levels,
style-associated levels and `numId=0` suppression keep precedence. An invalid effective list level
fails with `MALFORMED_DOCUMENT`; an overridden invalid default does not affect the result.

The extension `docx_document_defaults` lists effective properties supplied by this layer:
`outline_level`, `num_id`, and/or `list_level`. Table-list entries carry the same list in
`docx_list_items`. It is omitted when no supported default contributes; it does not fabricate a named
style or prefix source text. Reads are cached document-locally without creating missing defaults.
Run defaults, spacing, alignment, visual conditional formatting and page layout are not interpreted
by this semantic policy. See [Microsoft's document-defaults reference](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.docdefaults?view=openxml-3.0.1).

Table-cell paragraphs now receive the selected table style's semantic outline/numbering properties
between paragraph styles and `docDefaults`. Explicit `tblStyle` references take precedence over a
flagged default table style; missing references do not trigger fallback. Table-style `basedOn` chains
are resolved independently for each table, including nested tables. Effective layers are checked in
this order: derived-to-base `wholeTable` numbering properties, then derived-to-base general paragraph
properties. `wholeTable` outline overrides are ignored, matching the saved Word observation; general
style outline properties still apply. WholeTable numbering retains its existing, unverified policy.
Direct/named paragraph formatting and style-associated levels normally win; direct body outline9
yields to an active positional heading in table cells, matching the saved Word controls. Direct
heading outlines remain authoritative, and numbering subproperties merge individually. Table geometry and stored text remain unchanged.

Tables expose `docx_table_style` and optional `docx_table_style_source="default"`.
`docx_table_style_paragraphs` records code-point ranges, selected style IDs and contributed property
names; outline contributions include `outline_level`. Table-list ranges also retain
`docx_table_style_properties`. Whole-table ranges are removed from chunk metadata and kept in
original-element provenance when enabled. Missing/wrong-type/cyclic/overlong style chains emit
`docx_table_style_unresolved` and a diagnostic without using partial inheritance. Traversal checks
interruption and stops at 128 style hops.

Positional semantic conditions now include first/last rows and columns, horizontal/vertical bands,
and all four corners. Semantic precedence is whole table, vertical bands, horizontal bands, columns,
rows, then corners; later entries win, with derived-to-base style inheritance within each region.
Annotation `regions` keeps its existing canonical order independently of application precedence.
`tblLook` accepts its hexadecimal mask plus named boolean overrides; row `tblPrEx` settings have
precedence over table/style settings. Omitted look uses OOXML mask `0000` (bands enabled, edge
families disabled), rather than Word's different application default. Band sizes inherit from table
and style settings; absent sizes and zero disable the corresponding band family. Enabled edges
with resolved paragraph branches are excluded from inferred bands; a qualifying first edge shifts
the band origin.

Logical grid positions include declared grid width, `gridBefore`/`gridAfter`, `gridSpan` and legacy
horizontal/vertical merge footprints. Stored text from all parts of a merged cell uses its full
footprint. Membership is inferred from that stored geometry, then overlaid by `cnfStyle` row, cell
and paragraph masks/attributes in that order. Named attributes override a mask at the same level;
explicit corner bits are honored, otherwise corners follow effective edge membership. `tblLook`
still gates enabled condition families, and corners require both corresponding edge options.
Malformed flags/masks/sizes fail with `MALFORMED_DOCUMENT`; geometry budgets retain `TABLE_LIMIT`.
Semantic stripes require a band size in the effective table or inherited style settings; an absent
size disables that axis. Explicit zero also disables it. Stored `cnfStyle` flags do not enable an
axis whose size is absent. An enabled edge reserves stripe space only when the effective style
contains its paragraph branch; a missing branch leaves stripes at the original grid origin.
This reservation rule applies symmetrically to all four edges while preserving edge/corner and
cached membership rules. The horizontal behavior matches the pinned Word observation; the same
rule for columns is a consistent local policy, without independent vertical-band Word evidence.

Paragraph source ranges include ordered `regions` membership, retained in JSON/provenance. Unknown
semantic condition types still emit `docx_table_style_conditions_omitted` and a diagnostic. These
are explicit stored-grid policies, not full Word rendering parity: bidirectional visual order,
repeated rendered headers, compatibility-mode edge cases, stale/conflicting cached flags and full
visual formatting remain limits. See Microsoft's
[conditional ordering](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.tablestyleproperties?view=openxml-3.0.1),
[table-look rules](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.tablelook?view=openxml-3.0.1),
[Word's look-default deviation](https://learn.microsoft.com/en-us/openspecs/office_standards/ms-oe376/14b3f20d-4017-43c8-909c-d70620034b95)
and [conditional membership flags](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.conditionalformatstyle?view=openxml-3.0.1).





Raw table/paragraph annotations are retained in original-element provenance when chunking; whole-table reference offsets, revision ranges and list-item ranges are removed from canonicalized table chunks rather than copied with stale positions.

Apache POI uses the Log4j API; applications choose their logging provider. Without one, Log4j may print a provider diagnostic while extraction still succeeds. For standalone examples, the API's built-in simple logger can be selected with `-Dlog4j2.loggerContextFactory=org.apache.logging.log4j.simple.SimpleLoggerContextFactory`.

## Structured-table chunking

```java
var options = ChunkOptions.builder()
    .maxCharacters(500)
    .repeatTableHeaders(true) // default; repeats only when the complete fragment fits
    .build();
var chunks = Chunker.create().byTitle(result.elements(), options);
```

Compact table HTML and normalized cell text each obey the hard Unicode code-point limit. Rows and connected rowspan groups stay whole when possible; oversized rows split by cells, then by escaped cell text. Canonical HTML drops cosmetic/inline markup, flattens row-group wrappers, converts ordinary headers to `td`, and retains effective row/column spans. Plain text joins nonempty cells with spaces; original string whitespace/markup are available in provenance. Empty cells and span-covered rows remain structural content.

Leading `thead`/header-cell rows repeat on continuation fragments when they fit and the fragment starts at column zero. Large headers or incompatible partial-row columns suppress repetition rather than overflow or mislabel columns. `num_carried_over_header_rows` reports the actual repetition. A generated `table_leading_header_rows` hint retains header recognition when compacted tables are rechunked.

Each split carries a shared `table_id`, `chunk_index`, continuation flag and complete source provenance when enabled. Serialized originals must fit the codec's 16 MiB UTF-8 JSON limit; encoding now fails explicitly if the decoder could not read the result. Extension `table_row_start`/`table_row_end` and column bounds are half-open source ranges. `table_cell_fragments` records row-major source cell index, source row/column, effective spans, and half-open normalized-cell code-point offsets. Blank span-continuation cells carry geometry without duplicating data; synthetic repeated headers are excluded from ownership records. This allows fragments to be placed without assuming every fragment is a complete source row. Whole-table link/cell/alignment metadata is removed from fragments and retained in provenance.

`skipTableChunking(true)` passes Table/TableChunk objects through with IDs/text/metadata untouched, regardless of budgets or provenance settings. Tables remain isolated from narrative overlap. Structural fragments ignore narrative overlap and soft limits. If an oversized table has a window below 50 code points, it follows the pinned upstream text-only fallback; retained provenance includes its original HTML. If even one escaped character with its spans cannot fit a structural window, processing fails explicitly rather than dropping that character.

Structural input is bounded to 16,777,216 HTML code points, 100,000 rows, 1,000,000 source cells and materialized logical positions, 10,000 logical columns and DOM depth 256. Nested HTML tables and nonempty captions must be partitioned/flattened first or explicitly passed through. These bounds do not impose a heap limit or execution deadline.

The pinned upstream row/cell accumulators can emit HTML above the requested window; DocWeave intentionally enforces both budgets. Header suppression and blank span carries also have explicit local policies. Regression tests validate reconstruction and limits separately from the upstream comparison cases; complete structural-splitting parity is not claimed.

## Generated Javadocs

`mvn verify` generates per-module Javadocs such as `docweave-core/target/apidocs/index.html` and fails on documentation warnings/errors. Public contracts document defaults, Unicode behavior, exceptions, limits, thread safety, cancellation and compatibility boundaries. No preview flags are needed.

## Java 21+ examples

The examples use standard classes and public static main methods, compatible with Java 21 and later. With `DOCWEAVE_CP` generated from the reactor runtime dependencies as described in [Maven modules](docs/modules.md#build-tests-and-launchers), launch them without preview flags:

```sh
java --class-path "$DOCWEAVE_CP" examples/ChunkElements.java basic benchmarks/fixtures/elements.json /tmp/docweave-chunks.json
java --class-path "$DOCWEAVE_CP" examples/PartitionDocument.java benchmarks/fixtures/structured.html /tmp/docweave-elements.json
java --class-path "$DOCWEAVE_CP" examples/PartitionDocument.java benchmarks/fixtures/plain.txt /tmp/docweave-text-chunks.json by_title
java --class-path "$DOCWEAVE_CP" examples/PartitionDocument.java benchmarks/fixtures/structured.md /tmp/docweave-markdown-elements.json
java --class-path "$DOCWEAVE_CP" examples/PartitionDocument.java benchmarks/fixtures/quoted.csv /tmp/docweave-csv-elements.json
java --class-path "$DOCWEAVE_CP" examples/PartitionDocument.java benchmarks/fixtures/structured.xml /tmp/docweave-xml-elements.json
java --class-path "$DOCWEAVE_CP" examples/PartitionDocument.java benchmarks/fixtures/structured.docx /tmp/docweave-docx-elements.json
java --class-path "$DOCWEAVE_CP" examples/PartitionDocument.java benchmarks/fixtures/docx-content.docx /tmp/docweave-docx-content.json
java --class-path "$DOCWEAVE_CP" examples/PartitionDocument.java benchmarks/fixtures/large-table.html /tmp/docweave-large-table-chunks.json by_title
```

For a local PDF, the same launcher supports native extraction and optional chunking:

```sh
java --class-path "$DOCWEAVE_CP" examples/PartitionDocument.java /path/to/document.pdf /tmp/docweave-pdf-elements.json
java --class-path "$DOCWEAVE_CP" examples/PartitionDocument.java /path/to/document.pdf /tmp/docweave-pdf-chunks.json by_title
```

This simple launcher uses native PDF defaults. See [PDF configuration](docs/pdf-support.md)
for explicit OCR, layout providers and other extraction strategies.

Production code uses records, pattern switches, immutable collections, and virtual threads without preview APIs. Structured concurrency remains deferred while it is a preview API.

## Current limits

TXT classification uses simple list, length and punctuation heuristics; upstream NLP parity is not claimed. HTML preserves semantic DOM order without browser rendering, CSS visibility evaluation or asset fetching. Header/footer skipping is optional; scripts/styles/templates and explicit hidden subtrees are excluded. Nested tables are flattened into cell text with a warning.

Default input is capped at 10 MiB, output at 100,000 elements and HTML depth at 256. Markdown AST depth defaults to 128. Structured tables default to 100,000 rows, 1,000 columns, 1,000,000 total cells and 1,000,000 code points per cell. Limits include headers and apply during parsed-row consumption; they are not a heap limit or execution deadline. Byte decoding remains strict. XML defaults to depth 256 and 100,000 element nodes. DOCX is implemented; PPTX/XLSX and legacy Office remain pending.

Markdown implements CommonMark plus optional pipe tables, not every Markdown extension. Missing/surplus GFM cells follow the pinned library's normalization. Raw HTML can be preserved, skipped, or extracted through the opt-in combined DOM pipeline. DOM mode follows HTML prose classification and image-alt-text behavior; AST-specific image-reference, code-info and pipe-table alignment metadata are not retained. Parser output has synthetic regression coverage; full upstream parser parity is not claimed.

Only the supported text-element enum values can be deserialized; additional top-level fields fail explicitly. Generated chunk IDs and split-table IDs are UUIDs. Compression bytes may differ from Python's JSON formatting; compare decoded provenance. Original coordinate/probability precision follows upstream rounding.

Structured-table chunking is implemented for HTML-backed tables from HTML, Markdown, CSV/TSV and DOCX. Custom literal splitting separators and configurable table isolation are implemented, with pinned upstream option comparisons documenting matches and differences. Token limits remain deferred. The current implementation stages all input elements in memory.

See `docs/compatibility.md` for the implemented option matrix and reference-run limitations.


Conditional DOCX style validation now includes a [five-original corpus audit and separately labelled
controlled probes](benchmarks/corpus/docx-styles/README.md). The originals contain 14 tables but no
conditional outline/numbering definitions; 15 instrumented runs exercise source geometry and
metamorphic inheritance properties. This does not establish independent Word-rendering parity.

Independent DOCX semantic-style validation now has a [six-case Word review packet](benchmarks/review/word-style-cases/README.md). Word observations have been collected; all16 comparator-supported cases match the current implementation and four heading controls have policy regressions. Human review remains pending. Saved evidence is rechecked in private CI. A bounded seven-file external search found no conditional numbering/outline definitions.

Decoded `DocumentSource.Text` inputs now avoid a full UTF-8 encode/copy/decode round trip.
Loading validates Unicode and counts the UTF-8 footprint directly, retains the original String
except for leading-BOM removal, and allocates a bounded routing preview. See the
[source-loading allocation probe](benchmarks/text-source-allocation.md) for measured results
and limits. File/byte sources, parser models and output lists still materialize.

File loading now uses the open channel's size as an allocation hint to avoid buffer-growth and
final-copy arrays for stable files. Actual bytes still enforce the input limit; growth/truncation
are handled without treating the hint as authoritative. The
[allocation follow-up](benchmarks/text-source-allocation.md#file-loading-allocation-follow-up)
records measured reductions. Complete encoded payloads and decoded text still materialize.

Verified ASCII UTF-8 byte/file inputs now decode without an input-sized intermediate character
buffer. Other encodings and non-ASCII UTF-8 retain the strict reporting decoder. The
[decoder and partition memory probes](benchmarks/decoder-and-partition-memory.md) record
allocation and live-result observations, including the remaining materialization limits.

Text classification now recognizes list markers and counts title words without regex Matcher
objects or word arrays, preserving the existing rules. The [allocation investigation](benchmarks/classification-allocation.md)
records JFR evidence, comparison tests and the measured TXT reductions.

Element IDs retain their existing hash format while reusing one document-local SHA-256 engine
and formatting only the required digest bytes. The [emission allocation investigation](benchmarks/emission-allocation.md)
records measurements and compatibility tests; metadata remains deeply immutable.

Large identity messages now use bounded, reusable UTF-8 buffers while preserving legacy IDs.
A size-limited fast path keeps tiny documents inexpensive. See the [bounded hash investigation](benchmarks/bounded-hash-allocation.md)
for Unicode boundary tests, allocation results and CPU/memory limits.

Saved Word semantic evidence can be checked against a fresh local build without the VM; see
[reproducible Word comparison](benchmarks/word-style-validation.md). Matching saved values retains
human-review-pending status and does not establish general Word rendering parity.

See the [deferred-functionality audit](docs/deferred-functionality-audit.md) for remaining gaps,
validation limits and the next prioritized implementation task.

Java conventions follow the documented project style; see [Java style](docs/java-style.md)
for final-variable, functional-code and repeatable formatting guidance.

An opt-in nested-table preservation [contract](docs/nested-table-contract.md) is now documented,
with pinned default-output fixtures. HTML supports `PRESERVE_METADATA`: child grids, hierarchy,
root-relative intervals and child-local links/captions are retained on the root Table. Default output
and flattening diagnostics stay unchanged. Markdown also supports preservation in `EXTRACT_DOM` mode
with the optional HTML module installed. DOCX also supports preservation with its existing revision, merge and supplemental-part policies.
Markdown AST modes reject the option explicitly. Metadata has additional document-wide count/depth/payload limits.

```java
var options = PartitionOptions.builder()
    .nestedTableOptions(NestedTableOptions.preserveMetadata())
    .build();
var result = DocWeave.create().partition(DocumentSource.of(Path.of("nested.html")), options);
// Each root Table can contain nested_tables_version=1 and a flat nested_tables descriptor list.
```

For Markdown, also select `.markdownOptions(new MarkdownOptions(true, 128,
MarkdownOptions.RawHtml.EXTRACT_DOM, TableLimits.defaults()))` and install `docweave-html`.
The first discovered HTML extraction service must advertise preservation support; legacy providers
remain usable for default flattening and reject preservation. Missing HTML reports `UNSUPPORTED_FORMAT`;
unsupported mode/provider combinations report `UNSUPPORTED_OPTION`.

Rewritten chunks retain descriptors only inside requested original-element provenance; exact table
pass-through retains them directly. See the contract for coordinate spaces and budget accounting.

DOCX child descriptors include child-local `source_metadata` for lists, table styles, references,
revisions, cached fields and supplemental anchors. Source paths retain grid gaps and identify merged
owners when continuation content moves. Numbering is consumed once; default IDs/text/HTML/warnings
are unchanged. Stored `tblCaption` values are child captions. Budgets span all enabled document parts.
See the contract for the annotation whitelist and resource limits; stored structure does not establish
Word layout or upstream rendering parity.

The [owned nested-table corpus](benchmarks/nested-table-validation.md) exercises72 model-based
HTML/Markdown DOM/DOCX cases, exact default limit boundaries and combined DOCX merge/anchor behavior.
These are structural regressions, separate from independent rendering evidence.

The [nested-table memory profile](benchmarks/nested-table-memory.md) compares flattening and preservation
with pinned wide/deep inputs, exact calling-thread allocation counters and separately scoped live-heap/JFR
observations. The missing-span fast path now reduces preservation allocation on the pinned HTML/Markdown inputs;
see the report for fresh before/after measurements and limits.

A separate inline UTF-16 slice optimization avoids supplementary-character temporary arrays. The
[memory report](benchmarks/nested-table-memory.md) records isolated and full-pipeline allocation
comparisons, Unicode compatibility checks, and limits on interpreting these synthetic results.

Table HTML escaping also copies valid source UTF-16 slices while retaining entity/control/surrogate
rules and budget checks. The memory report records the modest full-pipeline allocation effect and
isolated measurements; broader independent DOCX fidelity coverage remains the next priority.
