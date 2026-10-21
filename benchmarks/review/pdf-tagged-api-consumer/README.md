# Packaged source-tagged PDF API consumer

The independent Java21 consumer passes using **six runtime JARs**: DocWeave core/PDF,
PDFBox, PDFBox IO, FontBox and Commons Logging. Compilation, execution and strict private
Javadocs use only those packaged JARs and the JDK. No reactor classes, test classes,
JUnit, Jackson, OCR, model, engine or other format is on its classpath. This exercises
the unreleased development snapshot, not the published beta.6 API.

The consumer builds an owned complete tagged PDF using JDK bytes, then calls only
public interfaces/factories. It checks class-derived colspan2, a preserved two-line
header with LF/HTML br, two authored empty logical cells, caption reference metadata,
separate ordinary caption and unclaimed inter-cell text, and one raster placement.
The strict logical-parent policy rejects the same gap-text table; the explicit cell-
envelope policy accepts it without cell coordinates or invented empty-cell rectangles.
Both complete outputs preserve every non-ASCII-whitespace code-point occurrence.
Neither that count nor the owned fixture proves semantic order or external accuracy.
Independent original-source class-span evidence remains an open gate.

The receipt binds the copied source, compiled Java21 class (major65), exact six JAR
identities, actual runtime and passed public assertions. Any additional classpath entry,
wrong artifact set or different JDK feature rejects receipt creation. Raw fixture bytes
are generated in memory; no third-party PDF, source text or models are redistributed.

Build the current core/PDF snapshot JARs before running the consumer:

```sh
JAVA_HOME=/path/to/jdk21 PATH=/path/to/jdk21/bin:$PATH \
  mvn -B -ntp -Dmaven.repo.local=/path/to/maven-cache \
  -pl docweave-pdf -am -DskipTests package

JAVA_HOME=/path/to/jdk21 bash benchmarks/review/pdf-tagged-api-consumer/run.sh \
  /path/to/maven-cache /path/to/new-consumer-output
```

The build packages modules; it does not claim to run tests. The runner itself installs
nothing and makes no network or publishing calls. It copies the current target JARs,
pinned PDFBox3.0.8/logging1.4.0 runtime artifacts and consumer source into a fresh output,
then compiles/runs/doc-checks there. For future dependency changes prepare a new pinned
consumer receipt rather than silently replacing this historical evidence. Java25 remains
deferred to PDF completion. The preceding full Java21 reactor passes1,125 tests/Javadocs;
this consumer-only change does not repeat that build.

The public configuration exercised is:

```java
var partitioner = PdfPartitioner.createWithTaggedTables(
        PdfOptions.defaults().withTableCellCoordinates(false),
        PdfNativeLayoutOptions.CellTextJoin.PRESERVE_LINE_BREAKS)
        .withTaggedTableGeometry(PdfTaggedTableGeometry.LOGICAL_CELL_TEXT_ENVELOPES);
var processor = DocWeave.create(PartitionerRegistry.of(List.of(partitioner)));
```

Unsupported source structures still fall back conservatively. This policy supplies no
OCR/model provider and does not solve untagged/borderless table structure. See the
[feature contracts](../../../docs/pdf-support.md) and
[external development results](../pdf-business-row-presentation/README.md).
