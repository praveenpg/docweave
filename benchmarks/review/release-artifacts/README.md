# Local source/documentation artifacts and PDF consumer

The explicit `release-artifacts` Maven profile attaches sources and Javadoc archives at
package. It does not configure signing, a deployment repository, a release version or
publication. Apache Maven Source Plugin3.4.0 uses jar-no-fork; existing Javadoc Plugin3.12.0
uses jar with the inherited strict documentation settings. Default builds retain their
existing Javadoc verification; the attachment profile is opt-in.

Both actual Java21 and25 clean profile builds pass899 tests/zero failures/errors/skips and
strict Javadocs. All16 library modules have binary, source and documentation JARs. The
parent remains a POM; two internal harness modules retain deployment skip and have no
source/docs attachments. "Publishable" counts in the JSON mean eligible under this Maven
configuration, not that licensing/release review is complete.

Archive checks verify source files against tracked Java bytes, project license content,
API documentation indexes, and the Microsoft TATR notice in all three ONNX archives.
The all-formats convenience artifact has no Java sources: its source/docs archives carry
a usage guide/license and direct readers to dependency API documentation. No fake API
class was introduced. No ONNX weights, traineddata, PDF or DOCX corpus files occur in the
inspected archives. This is an artifact inventory, not a complete dependency-license audit.

A standalone consumer POM declares only docweave-pdf, without inheriting the DocWeave parent.
Separate Maven repositories contain only newly staged parent/core/pdf project coordinates;
third-party/plugin files resolve through an explicit file mirror of the existing cache.
The consumer compiles with release21 and runs on actual21 and25, proving ServiceLoader PDF
discovery and extraction of one owned native-text PDF. Its six runtime JARs are DocWeave
core/PDF, PDFBox/pdfbox-io/fontbox and commons-logging. No other format/OCR/ONNX dependencies
are present. Both consumer runs use the Java25-built/Java21-target libraries; the two clean
reactor builds were separately inspected. This does not test optional native adapters,
tables/images in consumer applications or every operating system.

artifacts-java21.json and artifacts-java25.json contain independently inspected archive
hashes/entries/license paths. They are not expected to be byte-identical across JDKs.
consumer-java*.json retain exact runtime dependency paths and run output. manifest.json
contains source hashes, build logs/counts, artifact-check source, local install command and
consumer scripts/settings. consumer/ retains the actual formatted owned fixture generator
and standalone POM. No generated PDF, cache, JAR or external binary is committed.

Reproduction from repository root, with normal Maven dependencies available:

```sh
mvn -Prelease-artifacts clean verify
```

Repeat under each supported JDK. For the standalone consumer, first install the parent/core/PDF
artifacts locally using the profile, then build consumer/pom.xml against that repository and
run PdfConsumer with the runtime dependencies. The manifest preserves the exact isolated local
procedure used here, including file-mirror settings. A fresh Maven cache needs the pinned plugin
and dependency downloads before offline builds can work. Initial missing-cache attempts are
not counted as passing evaluations.

CI now builds this attachment profile after the ordinary test/Javadoc step on both JDKs and
retains source/docs archives with its existing private verification artifacts. Signing,
checksums, namespace verification and actual release/publication remain separate work.
