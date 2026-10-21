# Complete-source native PDF resource observations

This is operational evidence, not an extraction improvement or general capacity claim.
Two known originals run once each in separate Java 21 processes, with default native
options, `-Xms32m -Xmx256m` and a 60-second process deadline. The runtime consists only
of packaged core/PDF JARs and PDFBox, pdfbox-io, FontBox and Commons Logging. No reactor
classes, JUnit, JSON module, OCR provider or model runs on that classpath.

| Complete original | Native outcome | Time including discovery | Output | Linux process high-water RSS |
| --- | --- | ---: | --- | ---: |
| NVIDIA FY26Q4, 11 pages / 97,444 bytes | SUCCESS | 0.437 s | 1,052 elements: 1 image, 1,051 ordinary text; no tables; 1 warning | 188,512 KiB |
| Immunology News March 2025, 28 pages / 6,323,326 bytes | UNSUPPORTED_OPTION | 0.459 s to rejection | No successful extraction; output counts absent | 180,428 KiB |

The magazine contains arbitrary-angle text; default native text policy rejects it.
The existing explicit `PdfOptions.TextLayout.ORIENTED_LINES` option is documented in
[PDF support](../../../docs/pdf-support.md#opt-in-arbitrary-angle-native-lines), but
was not substituted after observing this result. Earlier oriented/source-image evidence
remains separate. The failure is retained in the two-input population. The financial
source's success does not solve its known table-structure gap.

Heap-pool peak-used sums are 43,163,256 and 52,118,144 bytes respectively. Pool peaks
need not occur simultaneously; their sum is not a measured simultaneous heap peak.
Linux VmHWM includes JVM startup, native allocations and the measurement process itself.
The 256 MiB heap cap is a chosen test condition, not a minimum memory recommendation;
process RSS can exceed that cap. These are single cold-process observations with no
warmup, repetitions, concurrent load or hardware normalization. No throughput or latency
service-level claim follows, and the failed magazine run is not full-document timing.

## Reproduction

Compile [the JVM probe](../../tools/MeasureNativePdfResources.java) with Java 21 and the
six pinned runtime JARs. Invoke it in a fresh process and use a fresh report path:

```sh
java -Xms32m -Xmx256m -cp "$probe_classes:$pdf_runtime_classpath" \
  MeasureNativePdfResources ORIGINAL.pdf EXPECTED_SHA256 FRESH.properties
```

Use the SHA256 values in the content-free properties reports. The probe checks identity
before extraction. It records typed extraction failures, writes their report, then exits
unsuccessfully by rethrowing the original failure. Identity mismatches and unexpected
non-partition failures remain unsuccessful runs without a fabricated extraction result.
Apply an external finite process deadline as above. Linux RSS is recorded only where
`/proc/self/status` is readable; other JVM observations are portable.

Strict private probe Javadocs, scoped formatting and the standalone Java 21 compilation
guard pass. Runtime source did not change for this measurement, so no additional full
reactor run was needed; the clipping change's 1,281-test Java 21 run remains applicable.
Sources and full logs remain outside Git; reports exclude source text and local paths.
