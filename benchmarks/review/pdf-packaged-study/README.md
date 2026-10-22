# Packaged PDF success-path study and independent-review packet

## Protocol (declared before measurement)

`protocol.properties` (SHA-256 `d7db9fb5...7ff0`) fixes four profiles, the schedule and gates
before any observation. Profiles: NVIDIA default native; NVIDIA with explicit
`RULED_AND_PARTIAL_TEXT`; magazine with explicit `ORIENTED_LINES` (distinct transfer workload);
magazine default (retained typed-rejection observation). Each profile runs 5 independent child
JVMs (`-Xms32m -Xmx256m`, 60 s process deadline). Each child verifies the source SHA-256, then
runs 1 cold extraction, 3 untimed warmups, 10 timed sequential extractions and 3 rounds of 4
concurrent virtual-thread extractions (26 attempts per process, 130 per profile). Gates: every
process meets its deadline; success profiles have no failures and magazine default keeps its
typed rejection; one logical-output hash per profile; Linux VmHWM below a declared 512 MiB
operational ceiling. No latency target was declared; times are recorded only.

`MeasurePackagedPdfStudy.java` (benchmarks/tools) is a JDK-only supervisor/worker that launches
children through `ProcessBuilder`, enforces the deadline, retains every exit code, timeout, typed
failure and child output tail, and compiles against only the six packaged runtime JARs in
`runtime-jars.sha256`: core and PDF JARs clean-built from `add2ab6` (verified to contain no local
candidate code), PDFBox, pdfbox-io and FontBox 3.0.8, Commons Logging 1.4.0. Host: Linux 7.2,
x86_64, i9-12900K (24 hardware threads), 128 GiB RAM; Java 21.0.12.

## Runs (all retained)

- `run1-harness-argument-defect.json`: every child exited with code 1 in about 20 ms because the
  worker's argument-count check was wrong; no extraction ran. Harness defect, fixed.
- `run2-noncanonical-hash-defect.json`: all children succeeded, but logical-output hashes differed
  between processes while being stable within each. The harness hashed `Map.toString()`, whose
  immutable-map iteration order is randomized per JVM. Harness defect, fixed by hashing a
  recursively key-sorted serialization. Not a product nondeterminism finding.
- `run3-java21.json`: the study result below.

## Results (run 3)

| Profile | Exits / deadline | Outcome | Distinct output hashes | Cold ms min/med/max | Warm ms min/med/max | 4-concurrent round ms | VmHWM KiB min/med/max |
| --- | --- | --- | --- | --- | --- | --- | --- |
| NVIDIA default | 5×0 / 5 | 130 success; 1,052 elements, 1 image, 0 tables | 1 | 445/455/474 | 55/59/68 | 72/81/148 | 332,568/347,764/362,464 |
| NVIDIA partial text | 5×0 / 5 | 130 success; identical output to default (0 tables) | 1 | 444/465/479 | 56/60/69 | 72/83/150 | 332,736/342,092/352,096 |
| Magazine oriented | 5×0 / 5 | 130 success; 2,847 elements, 94 images | 1 | 667/684/709 | 149/163/192 | 186/205/275 | 431,192/441,108/449,540 |
| Magazine default | 5×0 / 5 | 130 typed `UNSUPPORTED_OPTION` | n/a | 456/474/510 | 65/72/88 | 78/93/141 | 417,640/428,932/447,308 |

All declared gates pass. Cold includes first-use class loading in a fresh JVM; warm is after three
untimed warmups in the same JVM; concurrent is wall time for four simultaneous extractions with a
fresh service each. VmHWM covers the whole child process, including JVM startup, native and
repeated/concurrent extraction; it is not heap and not a minimum memory requirement.

The explicitly configured partial-text policy produced no tables on NVIDIA, so this study still
does not exercise a table workload. NVIDIA statement tables come from provider layouts; OCR, ONNX
and provider pipelines need their pinned resources and remain outstanding.

## Independent acceptance packet

Independent human review has not been completed; the gate stays outstanding. Objective
questions, without candidate-derived answers:

1. **Scientific sidebar order** (recent/next PLOS, eLife page 1): for each page-1 metadata sidebar
   block, does a human reader read it before, after or independently of the abstract? Inputs: the
   pinned PDFs and the sidebar/body run boxes recorded in the scientific diagnosis.
2. **Reference parenthesis spacing** (NVIDIA HTML tables 2 and 4, 36 indexed rows in the
   classification): should the reference protocol normalize `(5,491 )` to the PDF's `(5,491)`?
   This is a protocol decision; the extraction output is unchanged either way.
3. **1835 page number**: should the isolated page number be in the page transcription scope?
   The OCR misses it under both models.
4. **1778 subscriber-list rows** (degraded geometry diagnosis): for each wide intra-row gap, is the
   row one logical line or two columns? This decides whether over-split fragments are errors.
5. **1780 stamp and ruler**: should the vertical library stamp text count as page text (currently
   an unreferenced prediction) or as excluded non-content?

## Not established

No capacity/throughput SLA, multi-host behavior, total parser/decompression heap bound or
Java 25 result. The development sources are not held-out acceptance data.


## Integration review and harness version boundary

The original three study runs and original protocol/JAR identities above remain historical
at add2ab6. Their performance data are not relabelled as measurements of the newer runtime
or fingerprint. The integrated JDK-only harness now formats numbers with Locale.ROOT,
escapes JSON control/surrogate characters, uses a typed/length-delimited metadata fingerprint
with sorted keys, reads only a bounded child-log suffix and bounds force-kill joining.
Supervisor interruption destroys the child and preserves the interrupt flag. The fingerprint
covers element type/text/metadata, not element IDs, warnings or the entire result envelope.
Its encoding is recorded as `typed-length-prefixed-v1`; historical hash values use the old
representation and must not be compared across encodings.

Owned self-controls check a French locale, map-order invariance, string/list/number structural
distinctions, surrogate distinctions and JSON control escaping. The existing standalone
compatibility test compiles and runs those controls in a subprocess. No new product runtime
service or dependency is added. The supervisor records observations; gate conclusions still
require checking the declared protocol and complete report rather than assuming exit zero
means every scientific/operational gate is closed.

A separate reviewed smoke schedule runs all four profiles, one child and three attempts each,
under French locales. It preserves expected successes and the magazine default's three typed
rejections, stable typed fingerprints and finite deadlines. A JVM verifier checks every slot.
This is validation of the reviewed harness, not a repeat of the five-child/130-attempt study.
Sequential per-call timings exclude output fingerprinting, while concurrent round wall times
include task completion/fingerprinting and orchestration; RSS includes harness work too.
Do not compare those as pure parser throughput or infer minimum memory/capacity from them.

Fresh smoke reports/protocol and source/JAR/log hashes are recorded under integrated names
and integration-receipt.json. The four original profiles still contain no successful table
workload. OCR/ONNX/provider workload measurements and independent acceptance remain open.
