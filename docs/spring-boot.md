# Spring Boot: extract and chunk a PDF

Use Java21+ and a Spring Boot application. DocWeave is a regular Java library; no special
starter is required. Add these dependencies alongside your existing Spring dependencies:

```xml
<dependency>
    <groupId>io.docweave</groupId>
    <artifactId>docweave-pdf</artifactId>
    <version>0.1.0-beta.6</version>
</dependency>
<dependency>
    <groupId>io.docweave</groupId>
    <artifactId>docweave-chunking</artifactId>
    <version>0.1.0-beta.6</version>
</dependency>
```

Place these classes in a package scanned by your application. Imports below are complete;
add your application's package declaration to each source file.

## Register reusable services

```java
import dev.docweave.DocWeave;
import dev.docweave.chunking.Chunker;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers thread-safe DocWeave services using their public interfaces. */
@Configuration
public class DocWeaveConfiguration {
    /** @return the processor for installed document formats */
    @Bean
    public DocWeave docWeave() {
        return DocWeave.create();
    }

    /** @return the reusable chunking service */
    @Bean
    public Chunker chunker() {
        return Chunker.create();
    }
}
```

## Enable OCR for scanned PDFs

Add the optional adapter at the same version as your PDF dependency:

```xml
<dependency>
    <groupId>io.docweave</groupId>
    <artifactId>docweave-ocr-tesseract</artifactId>
    <version>0.1.0-beta.6</version>
</dependency>
```

Install Tesseract 5 and English (`eng.traineddata`) separately using the
[installation instructions](ocr-tesseract.md). The Maven dependency does not bundle or
install an executable or language models. The following examples use APIs available in beta.1.

Replace the native-only `docWeave()` bean above with this method, adding the listed imports
to `DocWeaveConfiguration`. Keep its existing `chunker()` bean and the service below.
Register only one `DocWeave` bean.

```java
import dev.docweave.ocr.TesseractOcr;
import dev.docweave.ocr.TesseractOptions;
import dev.docweave.partition.PartitionerRegistry;
import dev.docweave.partition.PdfOcrOptions;
import dev.docweave.partition.PdfOptions;
import dev.docweave.partition.PdfPartitioner;

/** @return a PDF processor that also recognizes pages without native text */
@Bean
public DocWeave docWeave() {
    final var ocr = TesseractOcr.create(TesseractOptions.defaults());
    final var pdf = PdfPartitioner.create(
            PdfOptions.defaults(), PdfOcrOptions.defaults(), ocr);
    return DocWeave.create(PartitionerRegistry.builder().register(pdf).build());
}
```

This resolves `tesseract` from the application's operating-system executable search path
and uses its installed English model. The explicit registry contains only this configured
PDF partitioner; it replaces automatic format discovery for this processor.
Native text is retained, and whole-page OCR runs only on pages without native text.
The existing `DocumentService` and chunking code need no changes.

### Configure executable, models and languages explicitly

For a service deployment, use trusted application properties instead of relying on its
executable search path. Replace the bean above with this alternative and add these imports:

```java
import org.springframework.beans.factory.annotation.Value;

import java.nio.file.Path;
import java.util.List;

/**
 * Creates OCR using installed resources and the adapter's default resource budgets.
 * @param executable trusted path to the Tesseract executable
 * @param tessdata trusted directory containing eng.traineddata
 * @return the configured PDF extraction service
 */
@Bean
public DocWeave docWeave(
        @Value("${docweave.ocr.executable}") final String executable,
        @Value("${docweave.ocr.tessdata}") final String tessdata) {
    final var defaults = TesseractOptions.defaults();
    final var engineOptions = new TesseractOptions(
            Path.of(executable), Path.of(tessdata), List.of("eng"),
            defaults.timeout(), defaults.maxConcurrentProcesses(),
            defaults.maxImagePixels(), defaults.maxPngBytes(), defaults.maxOutputBytes(),
            defaults.maxLines(), defaults.maxCharacters());
    final var ocr = TesseractOcr.create(engineOptions);
    final var pdf = PdfPartitioner.create(
            PdfOptions.defaults(), PdfOcrOptions.defaults(), ocr);
    return DocWeave.create(PartitionerRegistry.builder().register(pdf).build());
}
```

Supply the installed paths through environment variables:

```properties
docweave.ocr.executable=${TESSERACT_EXECUTABLE}
docweave.ocr.tessdata=${TESSERACT_TESSDATA}
```

`TESSERACT_EXECUTABLE` identifies the executable file; `TESSERACT_TESSDATA` identifies
the directory containing the model files. These are application configuration values,
not document or upload parameters. To recognize English and German, for example, install
both models and replace `List.of("eng")` with `List.of("eng", "deu")`.

### Mixed native text and scanned image regions

In either bean, replace `PdfOcrOptions.defaults()` with:

```java
final var ocrPolicy = PdfOcrOptions.defaults()
        .withSelection(PdfOcrOptions.Selection.IMAGE_REGIONS);
```

Pass `ocrPolicy` as the second argument to `PdfPartitioner.create`. This also recognizes
image regions on pages containing native text, with native-text masking and geometric
exclusion to reduce duplicates. It can omit ambiguous overlapping OCR lines; it does not
rasterize and recognize every native page indiscriminately.

### Scanned tables with visible closed grid lines

To request bounded cell OCR and structured table HTML, use this policy instead:

```java
final var ocrPolicy = PdfOcrOptions.defaults()
        .withSelection(PdfOcrOptions.Selection.IMAGE_REGIONS)
        .withTableDetection(PdfOcrOptions.TableDetection.RULED);
```

Again, pass `ocrPolicy` to the factory. This recognizes eligible closed, dark,
axis-aligned grids on the selected OCR pages or regions. It can require multiple OCR
calls per table. Borderless, broken, faint or skewed grids need other strategies;
this policy does not provide general table recognition. Structured output appears in
Table elements, including `text_as_html` metadata. See [PDF table and layout
options](pdf-support.md) for explicit word/layout providers and their requirements.

`PDF_EXTRACTION_LIMITATION` remains a capability notice with OCR enabled; its message
reflects the selected OCR/table policy. Missing executables or models fail explicitly
when recognition is attempted. Configuration creates no processes and downloads nothing.
The adapter defaults include two concurrent recognition calls per provider and a
60-second per-call deadline, including admission wait; these are not an end-to-end
PDF request deadline. See [OCR limits and failures](ocr-tesseract.md#resources-and-failures).

## Expose extraction and chunking through an interface

Save as `DocumentService.java`:

```java
import dev.docweave.core.Element;
import dev.docweave.partition.PartitionResult;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/** Application-facing PDF extraction and chunking contract. */
public interface DocumentService {
    /**
     * Partitions an uploaded document and groups its elements into chunks.
     * @param file uploaded PDF
     * @return chunks and extraction diagnostics
     * @throws IOException if reading or partitioning fails
     */
    ChunkedDocument extractAndChunk(MultipartFile file) throws IOException;

    /**
     * Keeps nonfatal extraction diagnostics alongside the chunk output.
     * @param chunks ordered chunks
     * @param warnings extraction warnings for review
     */
    record ChunkedDocument(List<Element> chunks, List<PartitionResult.Warning> warnings) {
        /** Copies collections so callers cannot mutate the result. */
        public ChunkedDocument {
            chunks = List.copyOf(chunks);
            warnings = List.copyOf(warnings);
        }
    }
}
```

Save the hidden implementation as `DefaultDocumentService.java` in the same package:

```java
import dev.docweave.DocWeave;
import dev.docweave.chunking.ChunkOptions;
import dev.docweave.chunking.Chunker;
import dev.docweave.partition.DocumentSource;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/** Implements the application contract with injected DocWeave services. */
@Service
final class DefaultDocumentService implements DocumentService {
    private final DocWeave processor;
    private final Chunker chunker;
    private final ChunkOptions options = ChunkOptions.builder()
            .maxCharacters(1500)
            .newAfterCharacters(1000)
            .combineTextUnderCharacters(0)
            .build();

    /**
     * Creates the service; Spring injects the single constructor automatically.
     * @param processor document extraction service
     * @param chunker element chunking service
     */
    DefaultDocumentService(final DocWeave processor, final Chunker chunker) {
        this.processor = processor;
        this.chunker = chunker;
    }

    @Override
    public ChunkedDocument extractAndChunk(final MultipartFile file) throws IOException {
        final var source = DocumentSource.bytes(file.getBytes(), file.getOriginalFilename());
        final var result = processor.partition(source);
        final var chunks = chunker.byTitle(result.elements(), options);
        return new ChunkedDocument(chunks, result.warnings());
    }
}
```

Inject `DocumentService` into your controller or another service and call
`extractAndChunk(file)`. Its result exposes `chunks()` and `warnings()`.

`maxCharacters` is the hard size budget in Unicode code points, not tokens;
`newAfterCharacters` is the soft grouping threshold. `combineTextUnderCharacters(0)`
keeps small title sections separate. Tables are isolated by default and may split into
structured fragments. Use `chunker.basic(result.elements(), options)` for sequential grouping.
Native PDF extraction does not infer headings, so title-aware chunking cannot recover headings
that the partitioner did not emit. Chunking does not create embeddings or store vectors.

The upload example materializes the entire file, extracted elements and chunks in memory.
For a servlet multipart application, configure limits appropriate to your service, for example:

```properties
spring.servlet.multipart.max-file-size=20MB
spring.servlet.multipart.max-request-size=21MB
```

Extraction is synchronous; in a reactive application, move blocking work off event-loop threads.
Scanned PDFs require the separately configured [Tesseract adapter](ocr-tesseract.md).
See [PDF scope](pdf-support.md) and [chunking policies](../README.md#structured-table-chunking).
Spring constructor injection follows the [official Spring documentation](https://docs.spring.io/spring-framework/reference/core/beans/annotation-config/autowired.html).
