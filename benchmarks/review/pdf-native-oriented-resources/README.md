# Explicit oriented-line resource observation

The same complete 28-page Immunology News March 2025 PDF used in the
[default-native resource observation](../pdf-native-resources/README.md) succeeds with
the existing explicit `ORIENTED_LINES` text policy. The original default-policy
`UNSUPPORTED_OPTION` outcome remains unchanged and visible. This is a targeted follow-up
configuration check after that rejection, not a prospectively selected quality experiment
or a newly implemented extraction algorithm. Defaults have not changed.

The isolated Java 21 process uses the same six packaged runtime JARs, `-Xms32m -Xmx256m`
and 60-second deadline. It extracts 2,847 elements: 2,753 ordinary text elements and all
94 source-image occurrences, with no tables and one limitation warning. The measured
call including configuration/discovery takes 0.655 seconds; Linux high-water RSS is
199,064 KiB. The heap-pool peak-used sum is 63,948,736 bytes, which is not a simultaneous
heap-peak measurement. These single-process observations do not establish throughput,
minimum memory, concurrent capacity, complete text fidelity or rendered image equality.

## Use the existing public configuration

```java
var options = PdfOptions.defaults()
        .withTextLayout(PdfOptions.TextLayout.ORIENTED_LINES);
var engine = DocWeave.create(PartitionerRegistry.of(
        List.of(PdfPartitioner.create(options))));
var result = engine.partition(DocumentSource.of(Path.of("magazine.pdf")));
```

Use the core/PDF public types and `java.util.List`/`java.nio.file.Path`. This policy
retains arbitrary-angle lines with approximate enclosing geometry; it does not infer
logical article order or join them into paragraphs. Mirrored/degenerate or unsupported
metrics can still fail. See [native angle support](../../../docs/pdf-support.md#opt-in-arbitrary-angle-native-lines).

## Reproduce

The existing [JVM probe](../../tools/MeasureNativePdfResources.java) now accepts an
optional fourth argument `ORIENTED_LINES`. Omitting it preserves default discovery;
other explicit profile strings are rejected before extraction. Its new reports record
`native_profile`. Earlier default reports remain historical and are not rewritten.

```sh
java -Xms32m -Xmx256m -cp "$probe_classes:$pdf_runtime_classpath" \
  MeasureNativePdfResources ORIGINAL.pdf EXPECTED_SHA256 FRESH.properties ORIENTED_LINES
```

Source identity must match the receipt; use a fresh report and a finite process deadline.
The probe compiles on Java 21, passes strict private Javadocs and scoped formatting,
and passes the standalone-source compilation guard. No runtime source changed and no
extra full reactor or Java 25 run was required. The last full runtime check remains
1,281 Java 21 tests with zero failures/errors/skips. No OCR engine, model, source labels,
new library dependency or external service is used.
