# PDF validation follow-up

Validation of development `60109f7`, including the opt-in trailing whitespace-cell row
policy. No production extraction code or default changed during this pass. Reports
are additive; historical source, reference, model and scoring identities are preserved.
This is validation evidence, not a release or a corpus accuracy estimate.

## Results

| Gate | Outcome | Evidence and boundary |
| --- | --- | --- |
| Java 21 reactor | Pass, reused unchanged runtime verification | 1,377 tests, zero failures/errors/skips; the prior independent review verified the exact production source |
| Final Java 25 reactor | Pass, newly executed | 1,377 tests, zero failures/errors/skips on Corretto 25.0.4; strict build documentation checks included |
| Independent packaged consumers | Pass | PDF, OCR and ONNX consumers on both JDKs; exact installed-JAR identities and Java 21 class major 65 verified |
| Former five-component native-layout API | Pass | Consumer compiled against the previous API, then run with only current runtime JARs on Java 21/25 |
| Scientific reading | Baseline reproduced | All result arrays unchanged: 59/143 native and 108/143 assembled; independent source reading-order review remains open |
| Scientific table spans | Pass | 43/43 exact span/text anchors under all six existing policies |
| Historical and degraded scans | Baseline reproduced | All eight source/model observations reproduce source pixels, recognized-text hashes, association and character metrics |
| Source images and crops | Pass on frozen controls | 94 source occurrences, four placement references, 18/24 qualified-figure slots; complete default and BOX_4X crop reports unchanged |
| Packaged external OCR | Pass after environment correction | 54 attempts across nine JVMs; both models exercised, 18 typed OCR_LIMIT outcomes, deterministic outputs; peak child JVM 335,588 KiB, engine memory excluded |
| Packaged fixed ONNX geometry/provider | Pass | 12 successful attempts; managed provider used through native PDF extraction, including concurrent calls and closure rejection |
| Packaged inferred-anchor limit | Pass | 12 typed TABLE_LIMIT attempts after provider invocation |
| Packaged real YOLOX/financial structure | **Memory gate fails** | All 12 extractions succeed with five tables each and identical logical output; three child JVM peaks exceed the frozen 1.5 GiB ceiling |
| Cross-producer trailing-row transfer | No regression, positive gain not demonstrated | Complete Apple three-page and Amazon fourteen-page originals: five/seven provider tables, zero added rows, identical logical payloads |
| Upstream source freshness | Pass after source triage | Four additional main commits reviewed; no PDF source changes, release/reference pins preserved |
| Independent human acceptance | **Open** | Source order and transcription/reference-scope decisions cannot be established by automated checks |

The fresh Java 25 run first encountered sandbox restrictions on existing local mock
HTTP servers; the complete rerun with socket access passed. A crop/OCR launch initially
inherited an unusable DISPLAY. Explicit headless execution corrected that environment
without changing extraction settings, gates or references. Those initial failures are
retained privately. The OCR headless protocol is an additive derivative of the original.

## Cross-producer capture and replay

`ValidateTrailingRowTransfer` runs the pinned public YOLOX/financial structure provider
once per original page. A second extraction uses the complete recorded table population,
including typed failures, only after verifying the exact input PNG hash and capture
consumption. Baseline and candidate differ only in trailing-row policy; both use the
existing section-row option. No references, ROIs or selected-page hints enter extraction.

- Apple Q3 2026: original Word/Quartz document, three pages, five provider/output tables.
- Amazon Q4 2025: original Workiva/Wdesk document, fourteen pages, seven tables.
- Both baseline and candidate complete successfully and have identical ordered
  type/text/metadata payloads. Generated element IDs are excluded.

The observation is useful cross-producer non-regression coverage. It is not evidence of
positive recovery: neither source triggers an extension. Do not fabricate truncated
proposals or tune models to call this a natural positive transfer. That acceptance gate
needs an independently supported original where a provider actually misses trailing rows.

## Packaged ONNX/provider study

`ValidatePackagedProviders` compiles against exactly eight packaged runtime JARs: core,
PDF, ONNX adapter, ONNX Runtime, PDFBox, pdfbox-io, FontBox and Commons Logging. No
validation/test classes or JSON library are on the extraction classpath. The owned graph
is supplied as an explicit external file, not packaged in the library or downloaded.

Before measurement, the protocol froze three profiles, three JVMs/profile, four
attempts/JVM (cold, sequential, two simultaneous virtual-thread calls), a 180-second
child deadline, `-Xmx512m`, and a 1,572,864 KiB process high-water ceiling. A child owns
one managed provider, shared with admission concurrency two and closed before checking
that another call is rejected. Both fixed geometry and real original-document profiles
must emit at least one Table per successful extraction. The limit profile must yield
TABLE_LIMIT on every attempt. All observations and failing child exits are retained.

Real-model memory peaks: **1,855,972**, **1,843,752**, **1,930,616 KiB**. All exceed the
frozen ceiling. Model execution, within/across-process determinism, extraction, concurrent
calls and closure checks pass; the overall study fails because of memory. No ceiling
was raised and no parameter search followed. This is a failed operational qualification,
not proof of a leak or a violation of an advertised total-memory bound. The encoded model
and Java heap caps do not bound ONNX native allocations. VmHWM includes native ONNX memory
inside each child; OCR engine memory in the separate OCR study is excluded.

Next bounded investigation: profile allocation and retention under a separately frozen
single-admission workload to distinguish live concurrent inference from retained buffers.
Do not rewrite this study or report the original ceiling as passed.

## Validation tooling review

The standalone consumer verifier contained a historical hard-coded source commit.
It now records the actual checkout commit using bounded Git execution. The original
mislabelled receipt is retained privately; the corrected receipt was regenerated from
the same successful consumer runs. Exact JAR hashes remain authoritative; a Git commit
alone is not proof of build provenance. Strict private Javadocs and compilation pass
for the new launchers and changed verifier. The final standalone source/API checks pass.

An initial generic scan comparator expected eight observations per input, while each
historical/degraded subset has four. The existing wide-gap replay checker was then used
with both complete subsets; all eight source/model identities and recorded metrics pass.
No population guard or scoring rule was weakened.

## Reproduction

Use Java 21 for the studies and the current packaged JARs identified by the manifests.
Local source/model paths and hashes are explicit in `transfer.properties` and
`provider-protocol.properties`. Models must already exist; nothing is fetched implicitly.

```sh
javac --release 21 -cp "$validation_cp" -d "$tool" \
  benchmarks/tools/ValidateTrailingRowTransfer.java
java -Djava.awt.headless=true -Xmx768m -cp "$tool:$validation_cp" \
  dev.docweave.validation.ValidateTrailingRowTransfer transfer.properties NEW_PRIVATE_DIRECTORY

javac --release 21 -cp "$eight_runtime_jars" -d "$tool" \
  benchmarks/tools/ValidatePackagedProviders.java
java -cp "$tool:$eight_runtime_jars" ValidatePackagedProviders supervise \
  provider-protocol.properties "$tool:$eight_runtime_jars" NEW_PRIVATE_DIRECTORY
```

The transfer capture includes source text and must stay private. Numeric summary files
here contain only identities, counts, hashes and measured values. Use fresh output paths.
See the prior evidence directories for scientific/scan/crop source replay commands and
the standalone consumer runner. The private validation packet retains exact commands,
full logs, initial environmental failures, raw captures and the human-review checklist.
Library snapshot installs used only the local `/tmp/docweave-m2` cache. No publication,
release, repository synchronization, commit or push was performed during validation.
