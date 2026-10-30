# Third-party attribution

DocWeave project code is licensed under Apache License2.0, except separately identified
third-party material. This guide is included in binary/source/documentation archives.
Dependencies remain separate Maven artifacts; they are not shaded into DocWeave JARs.
Preserve their original licenses/notices when distributing an application with dependencies.
The list is the resolved runtime union of all formats plus optional OCR/layout adapters at
snapshot0.1.0-beta.8; selecting PDF alone does not include every dependency below.

## Compatibility reference and adapted geometry

Compatibility analysis references Unstructured-IO/unstructured at historical commit
347fbbfd3e84e228017f125e5c5dbdc326ef7de2 (Apache License2.0):
https://github.com/Unstructured-IO/unstructured/blob/347fbbfd3e84e228017f125e5c5dbdc326ef7de2/LICENSE.md
No upstream Python implementation or Python runtime is bundled with the library. Historical
comparison pins and later upstream review results are distinct from a general parity claim.

The optional ONNX adapter's TableTopology implementation is inspired by Microsoft's
Table Transformer geometry postprocessing. Its Microsoft MIT notice is retained in
META-INF/Microsoft-TATR-LICENSE.txt in the adapter, sources and documentation archives.
Source reference: https://github.com/microsoft/table-transformer/tree/16d124f616109746b7785f03085100f1f6247575

## Runtime dependencies

The labels below summarize effective published POM metadata, with CurvesAPI's BSD terms
also checked against its pinned source license. They do not replace the full license texts
or describe every bundled subcomponent's terms. For example, Jackson core contains separate
FastDoubleParser/Schubfach notices, and ONNX Runtime contains ThirdPartyNotices.txt covering
its bundled native components. Keep those upstream artifacts and notices intact.

| Resolved coordinate | Declared main license |
| --- | --- |
| `org.apache.pdfbox:pdfbox:3.0.8` | Apache-2.0 |
| `org.apache.pdfbox:pdfbox-io:3.0.8` | Apache-2.0 |
| `org.apache.pdfbox:fontbox:3.0.8` | Apache-2.0 |
| `commons-logging:commons-logging:1.4.0` | Apache-2.0 |
| `tools.jackson.core:jackson-databind:3.1.5` | Apache-2.0 |
| `com.fasterxml.jackson.core:jackson-annotations:2.21` | Apache-2.0 |
| `tools.jackson.core:jackson-core:3.1.5` | Apache-2.0 |
| `org.jsoup:jsoup:1.23.2` | MIT |
| `org.commonmark:commonmark:0.30.1` | BSD-2-Clause |
| `org.commonmark:commonmark-ext-gfm-tables:0.30.1` | BSD-2-Clause |
| `org.apache.commons:commons-csv:1.14.1` | Apache-2.0 |
| `org.apache.poi:poi-ooxml:5.5.1` | Apache-2.0 |
| `org.apache.poi:poi:5.5.1` | Apache-2.0 |
| `org.apache.commons:commons-math3:3.6.1` | Apache-2.0 |
| `com.zaxxer:SparseBitSet:1.3` | Apache-2.0 |
| `org.apache.poi:poi-ooxml-lite:5.5.1` | Apache-2.0 |
| `org.apache.xmlbeans:xmlbeans:5.3.0` | Apache-2.0 |
| `org.apache.commons:commons-compress:1.28.0` | Apache-2.0 |
| `org.apache.commons:commons-lang3:3.18.0` | Apache-2.0 |
| `com.github.virtuald:curvesapi:1.08` | BSD-3-Clause |
| `org.apache.logging.log4j:log4j-api:2.24.3` | Apache-2.0 |
| `org.apache.commons:commons-collections4:4.5.0` | Apache-2.0 |
| `commons-io:commons-io:2.21.0` | Apache-2.0 |
| `commons-codec:commons-codec:1.20.0` | Apache-2.0 |
| `com.microsoft.onnxruntime:onnxruntime:1.23.2` | MIT |

The optional `docweave-source-s3` module (not part of `docweave-all`) adds the AWS SDK for Java 2.x and its
Apache HttpClient 5 transport. Its resolved runtime dependencies are:

| Resolved coordinate | Declared main license |
| --- | --- |
| `software.amazon.awssdk:*:2.55.13` (s3, apache5-client and their SDK modules, including `third-party-jackson-core`) | Apache-2.0 |
| `software.amazon.eventstream:eventstream:1.0.1` | Apache-2.0 |
| `org.apache.httpcomponents.client5:httpclient5:5.6.4` | Apache-2.0 |
| `org.apache.httpcomponents.core5:httpcore5:5.4.3` | Apache-2.0 |
| `org.apache.httpcomponents.core5:httpcore5-h2:5.4.3` | Apache-2.0 |
| `org.reactivestreams:reactive-streams:1.0.4` | MIT-0 |
| `org.slf4j:slf4j-api:1.7.36` | MIT |

The AWS SDK JARs carry their own NOTICE files, including notices for bundled third-party code; keep them intact.
Its tests use Adobe S3Mock 5.2.3 (Apache-2.0) as a separate process and optionally SeaweedFS; neither is a runtime
dependency.

The exact POM/JAR hashes, inherited license names/URLs and embedded notice locations are
recorded in benchmarks/review/third-party-audit/runtime-inventory.json. That repository
report describes this snapshot; regenerate the inventory when dependencies change.

## Supplemental upstream license copies

The resolved CurvesAPI1.08 and SparseBitSet1.3 JARs contain no embedded license file.
The DOCX module therefore includes unmodified source-release copies:

- META-INF/CurvesAPI-LICENSE.txt: Graph Builder's BSD-3-Clause terms from
  https://github.com/virtuald/curvesapi/blob/a39c6d234bd1b70a324a8cf4f5a2b0e1f2780c7f/license.txt
- META-INF/SparseBitSet-LICENSE.txt: Apache License2.0 from
  https://github.com/brettwooldridge/SparseBitSet/blob/46c1f7412b992aa8c72f5ce4601013b9160110a4/LICENSE

The optional ONNX adapter also includes META-INF/ONNX-Runtime-LICENSE.txt, the runtime's
own MIT license from https://github.com/microsoft/onnxruntime/blob/a83fc4d58cb48eb68890dd689f94f28288cf2278/LICENSE.
The dependency JAR's ThirdPartyNotices.txt remains in that unmodified upstream artifact.
For generated Javadoc archives, these supplemental copies are under resources/META-INF.
The main project license does not replace any of these terms.

## Tests, tooling and external inputs

JUnit and benchmark/reference tooling are development-only; they are not runtime library
dependencies. Test binaries and test sources are not attached as release artifacts.
The three small ONNX test graphs are project-owned constant/loop fixtures, not model weights;
their generation/provenance is in docweave-layout-onnx/src/test/resources/onnx/README.md.
Tracked owned DOCX fixtures have generation/provenance records in benchmarks/.

External original PDFs/images, traineddata, model weights and conversion/reference runtimes
remain fetch-only local resources with their own terms and pinned manifests. Optional
Tesseract execution requires the caller's installed engine/data. Nothing here grants rights
to redistribute arbitrary source documents, trained models or externally generated reports.
Review repository evidence/excerpts separately before making the source repository public.
This runtime/artifact inventory is not a vulnerability scan or a blanket rights clearance
for every file in the repository.
