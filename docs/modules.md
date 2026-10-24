# Maven modules and selective dependencies

Java 21 is the minimum build/runtime version; CI verifies Java 21 and 25.

The root is the `io.docweave:docweave-parent` aggregator/parent POM. All modules currently
use version `0.1.0-beta.8` for the local candidate. Java packages now use `io.docweave`;
applications upgrading from earlier betas must [migrate imports and rebuild](package-migration.md).
This candidate has not been published; earlier published versions retain their original packages.

| Artifact | Responsibility | Runtime dependencies |
| --- | --- | --- |
| docweave-core | Elements, facade, source loading, options, registry and parser SPI | JDK only |
| docweave-txt | TXT grouping and size policies | core |
| docweave-html | HTML parsing, charset declarations and DOM extraction | core, jsoup |
| docweave-markdown | CommonMark AST and pipe tables | core, CommonMark and GFM tables |
| docweave-csv | Comma-delimited strategy | core, delimited support |
| docweave-tsv | Tab-delimited strategy | core, delimited support |
| docweave-xml | Secure streaming XML extraction | core, JDK XML |
| docweave-pdf | Native quarter-turn PDF text/layout, ruled/merged and aligned numeric tables, image placements/payloads; optional provider-based OCR and scanned complete ruled tables | core, PDFBox |
| docweave-layout-onnx | Explicit local pinned ONNX table-structure provider; no discovery/download | PDF module, ONNX Runtime; excluded from convenience artifact |
| docweave-ocr-tesseract | Explicit optional managed external OCR for textless/mixed-image PDF pages; executable/models installed separately | PDF module; excluded from convenience artifact |
| docweave-docx | DOCX archive and WordprocessingML extraction | core, POI and its required libraries |
| docweave-delimited | Internal quoted-record extraction shared by CSV/TSV | core, Commons CSV and its required libraries |
| docweave-json | Element JSON and compressed provenance codec | core, Jackson |
| docweave-chunking | Basic/title/structural-table chunking | core, JSON module, jsoup |
| docweave-batch | Virtual-thread document and element-file pipelines | core, chunking and JSON modules |
| docweave | Convenience dependency with all parsers and optional pipeline services | format and pipeline modules above; excludes external OCR and ONNX layout |
| docweave-integration-tests | Cross-module behavior and reference fixtures | test harness; deployment skipped |
| docweave-validation | Independent corpus/evidence diagnostics and upstream status | test scope only; installation/deployment skipped |

Parent dependencyManagement centralizes versions without adding runtime libraries to core.
JUnit is inherited in test scope only. Format implementations remain hidden and public service
contracts remain interfaces. Shared table-text escaping no longer requires jsoup: it is compared
against the prior escaping behavior across all BMP characters and seeded supplementary strings.

## DOCX-only application

After local installation or a future publication, select just this dependency:

```xml
<dependency>
    <groupId>io.docweave</groupId>
    <artifactId>docweave-docx</artifactId>
    <version>0.1.0-beta.8</version>
</dependency>
```

```java
import io.docweave.DocWeave;
import java.nio.file.Path;

var processor = DocWeave.create();
var result = processor.partition(Path.of("report.docx"));
```

This installs only the DOCX strategy and its core/POI dependencies. It does not bring in jsoup,
CommonMark, Commons CSV, Jackson, other format strategies, chunking or batch services. Add
`docweave-json` for JSON, `docweave-chunking` for chunking, or `docweave-batch` for batch pipelines.
Those optional services have their own dependencies; structural chunking intentionally uses jsoup
and JSON provenance. To retain the former all-formats convenience dependency, use
`io.docweave:docweave:0.1.0-beta.8` (the child under `docweave-all/`).

## Runtime strategy discovery

`DocWeave.create()` and default registries discover installed format modules through the JDK
`ServiceLoader` and the caller's context class loader. Core alone advertises no formats. Adding a
format artifact makes that format available without editing core; absent formats fail with
`UNSUPPORTED_FORMAT`. Explicit registries remain supported and explicit builder registrations
replace discovered strategies. Duplicate installed format keys are rejected.

The `PartitionerProvider` interface is the discovery SPI. JDK classpath discovery requires its
small provider bridges to be public with public no-argument constructors. These are infrastructure
entry points; actual parsers returned by `create()` remain package-private `Partitioner`
implementations. Provider resources must remain available when assembling an application; a shaded
JAR must merge service descriptors rather than overwrite them.

HTML head-charset sniffing belongs to the installed HTML provider. Markdown's `EXTRACT_DOM` mode
uses the optional `HtmlExtraction` service: add `docweave-html` when selecting that mode. Default
AST Markdown works without HTML/jsoup; DOM mode without the HTML module fails with a typed
`UNSUPPORTED_FORMAT` explaining the required artifact. The all-formats dependency includes both.

These are Maven modules running on the Java classpath, not JPMS modules. Preserving existing
packages means internal partition helpers span artifacts in the same package. All artifacts must
share the normal application class loader; split packages preclude using these JARs together as
JPMS named modules. A JPMS/package redesign remains separate from this dependency split.

## Build, tests and launchers

```sh
mvn clean verify
mvn -pl docweave-docx -am verify
```

The first command validates the whole reactor and strict per-module Javadocs. The second builds
core and DOCX only, including tests that confirm no unrelated parser/JSON classes are present.
Every format has standalone Maven-classpath discovery/parsing/dependency-isolation tests. Core
service-descriptor tests verify duplicate/mismatched provider rejection and explicit overrides. The
existing reference/behavior suite and resources live in `docweave-integration-tests/src/test`;
its working directory stays the repository root for unchanged benchmark-fixture paths.

Per-module Javadocs live under `<module>/target/apidocs/`. Generate a complete launcher classpath:

```sh
mvn -pl docweave-all -am package org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath -DincludeScope=runtime -Dmdep.outputFile=target/runtime-classpath.txt -DskipTests
DOCWEAVE_CP="$(cat docweave-all/target/runtime-classpath.txt)"
java --class-path "$DOCWEAVE_CP" examples/PartitionDocument.java benchmarks/fixtures/structured.docx /tmp/docweave-elements.json
```

The generated classpath includes the reactor's actual module JARs and runtime libraries. The old
root `target/classes` and root JAR are obsolete; regenerate temporary classpath files after this
migration. Use `mvn install` to make the local candidate available to separate local Maven projects.
Benchmark source fingerprints now include module-relative source paths; historical evidence and
its old fingerprints remain historical rather than being relabeled as new measurements.

Publication remains a separate explicit release operation;
see [publication identity](publishing.md). This refactor performs no publication or visibility change.

Markdown nested-table metadata preservation requires EXTRACT_DOM and a capable optional HtmlExtraction
service (provided by docweave-html). AST modes reject preservation; missing HTML reports
UNSUPPORTED_FORMAT. Older custom HTML services default to unsupported preservation but retain
flattening compatibility. No HTML dependency was added to docweave-markdown.

DOCX nested-table preservation uses only the DOCX module and JDK-only core. Child semantic snapshots
and merged-owner paths do not add HTML/Markdown/JSON dependencies; optional JSON serialization and
chunking continue to operate on the common immutable element model.

## PDF-only application

Select `io.docweave:docweave-pdf` for PDF without DOCX/HTML/JSON dependencies. See
[PDF usage, limits and milestones](pdf-support.md). Core alone detects a PDF but fails with
UNSUPPORTED_FORMAT when no PDF strategy is installed.
