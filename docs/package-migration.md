# Java package migration in local beta.8

Version `0.1.0-beta.8` changes Java packages from `dev.docweave` to `io.docweave` across all
modules. Maven groupId remains `io.docweave`; artifactIds and the Java 21 baseline are unchanged.
This is a source and binary breaking change. There are no forwarding classes or compatibility
aliases. Previous releases and their artifacts are unchanged.

## Upgrade

1. Install the local candidate from the repository root with `mvn clean install` on Java 21.
   Only parent/core beta.8 is available from Maven Central. The other beta.8 modules still require a local build.
2. Set every DocWeave dependency to `0.1.0-beta.8`; avoid mixing module versions.
3. Replace imports and fully qualified names beginning `dev.docweave` with `io.docweave`.
   Update reflection/configuration references, mock rules and custom adapters, then rebuild.
4. For custom ServiceLoader providers, implement the new SPI, rename the descriptor to
   `META-INF/services/io.docweave.partition.PartitionerProvider`, and update its provider class names.
   Custom HTML extraction providers similarly use `io.docweave.partition.HtmlExtraction`.
5. Regenerate classpaths and clean stale compiled classes before running consumers. Old compiled
   extensions require recompilation. Existing Studio builds still use their pinned beta.7 library;
   migrate Studio separately after its in-progress work is coordinated.

```xml
<dependency>
    <groupId>io.docweave</groupId>
    <artifactId>docweave-pdf</artifactId>
    <version>0.1.0-beta.8</version>
</dependency>
```

```java
import io.docweave.DocWeave;
import io.docweave.chunking.Chunker;
import io.docweave.json.ElementJson;
```

The local migration updates runtime/test source paths, SPI resources, active examples, validation
launchers and source-inspection rules. Frozen benchmark reports, corpus receipts and older release
notes retain historical names and fingerprints. They describe earlier artifacts and must not be
rewritten to imply that historical measurements used beta.8. No new extraction-quality claim or
JPMS support follows from this namespace change. No remote publication is part of this task.
