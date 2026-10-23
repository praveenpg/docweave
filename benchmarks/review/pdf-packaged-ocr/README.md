# Packaged OCR workload

The [packaged study](../pdf-packaged-study/README.md) and the
[packaged table study](../pdf-packaged-tables/README.md) ran only native-text extraction. This
study covers the next uncovered workload: explicit external-engine OCR through packaged JARs. It
is validation work only. No extraction code, option, default, reference or score changed.

## Frozen scope and gates

`protocol.properties` (SHA-256 `ccb29db1…7e27e`) was written before any measurement.

- **Input.** One input is pinned, `historical-1835-container.pdf` (SHA-256 `d4800eb0…61395`).
  It is the frozen historical harness's own image-only container for the 1835 scan, written by
  `WriteHistoricalContainer.java.txt` through the harness's private decode and container
  methods. Its 300 DPI PDFBox render reproduces the harness's recorded source RGB,
  `945b6c67…a9daf`. The container is derivative and is not redistributed.
- **Engine.** Tesseract 5.5.3 with the pinned `deu` and `deu_latf` tessdata_best models and the
  frozen historical settings: one engine thread (`OMP_THREAD_LIMIT=1`), automatic page
  segmentation, engine-default thresholding and the declared 300 DPI.

There are three profiles:

| Profile | OCR policy | Expected outcome |
| --- | --- | --- |
| `historical-ocr-deu` | `TESSERACT:deu:300` | success |
| `historical-ocr-fraktur` | `TESSERACT:deu_latf:300` | success; a second model on the same page |
| `historical-ocr-limit` | `TESSERACT_LIMIT:deu:300`, an OCR raster budget one pixel below the page | typed `OCR_LIMIT` before any engine process |

Each profile runs three independent child JVMs with `-Xms32m -Xmx256m` and a 180-second deadline.
Each child performs one cold attempt, one warm-up, two sequential attempts and one round of two
concurrent virtual-thread attempts: 6 attempts per child, 18 per profile, 54 in total.

The gates are:

- every child exits before its deadline;
- the success profiles have no failures;
- the limit profile fails every attempt with `OCR_LIMIT`;
- each success profile has one logical-output hash across all its JVMs;
- the two models produce different outputs, so both are actually exercised;
- child JVM VmHWM stays below 512 MiB.

No latency target was declared.

## Harness and classpath

`MeasurePackagedPdfStudy` gains an explicit `TESSERACT:LANGUAGE:DPI` /
`TESSERACT_LIMIT:LANGUAGE:DPI` policy. It loads the optional OCR module reflectively, so earlier
native-only protocols still compile against the core and PDF JARs alone. The trusted executable
and model directory come from declared `-D` JVM options.

The study compiled and ran on Java 21 against only the seven packaged runtime JARs in
`runtime-jars.sha256`: core, PDF and OCR-Tesseract, plus PDFBox, pdfbox-io, FontBox and Commons
Logging. The PDF and OCR JARs were packaged from the current local working tree, and the core
JAR's classes were confirmed identical to the current build. No test classes, JSON library or
layout model were on the extraction classpath. `CheckPackagedOcrStudy.java.txt` checks every gate
from the report and runs on the validation classpath, since it needs Jackson.

The harness source was formatted with the pinned formatter after this run. The change is layout
only, and the formatted harness passes the standalone compile and self-test in the full build.

## Results (`run1-java21.json`, `checks.txt`)

| Profile | Attempts | Outcome | Distinct output hashes | Cold ms | Warm sequential ms | Two-concurrent round ms | Child VmHWM KiB |
| --- | ---: | --- | ---: | --- | --- | --- | --- |
| `deu` | 18 | 18 success, 22 elements | 1 | 1,695–1,852 | 1,478–1,492 | 1,494–1,500 | 326,704–343,456 |
| `deu_latf` | 18 | 18 success, 22 elements | 1 (differs from `deu`) | 1,744–1,755 | 1,461–1,489 | 1,477–1,501 | 337,748–345,552 |
| limit | 18 | 18 typed `OCR_LIMIT` | n/a | 125–129 | 3.5–3.9 | 8.3–9.1 | 157,148–157,948 |

All declared gates pass. The limit profile confirms that the page budget is checked before
rendering into the engine and fails with the documented typed code. The two concurrent attempts
used separate engine instances, each with its own admission bound, so a round took about as long
as one sequential attempt.

## Limits

- VmHWM covers only the child JVM. Each OCR attempt also runs a separate native engine process,
  whose memory is not measured here. This is not a total memory bound.
- A process deadline is not evidence that an engine call or a running PDFBox render is
  interruptible.
- This is one historical page on one host. It is not a capacity, throughput or accuracy claim.
  Recognition quality is reported separately by the historical OCR evidence.
- ONNX layout and table-provider workloads remain unmeasured as packaged workloads.
- Independent acceptance review remains open.
