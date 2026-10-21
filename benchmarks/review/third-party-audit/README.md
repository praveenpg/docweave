# Runtime dependency and archive attribution review

Scope: the resolved runtime union of the all-formats convenience artifact plus explicit
Tesseract and ONNX adapters, using the current snapshot dependencies. This is25 external
Maven artifacts, not the dependency set of a PDF-only application. It excludes test/build
and external reference-tool environments. No dependency version or extraction algorithm
changes in this checkpoint.

Maven resolves an independent inventory POM; its JAR manifest exposes the runtime classpath.
A cached-only Maven model builder reads effective license declarations, including inherited
metadata. runtime-inventory.json records leaf POM/JAR hashes, declared license names/URLs,
normalized main-license labels and actual embedded notice paths/hashes. The63 leaf/parent/BOM
POM fingerprints in model-source-hashes.json preserve the consulted metadata. The helper's
repeated metadata output is identical. Main-license counts20Apache-2.0/2MIT/2BSD-2-Clause/1BSD-3-Clause
do not classify every embedded subcomponent, font or native component's individual terms.

## Findings and corrections

- The root attribution guide was stale and absent from library archives. Update it to the
  selected Apache project license and actual runtime dependencies; include it as
  META-INF/THIRD_PARTY_NOTICES.md in every library binary/source JAR and in documentation
  resources/META-INF (the class-free convenience guide JAR uses META-INF directly).
- CurvesAPI1.08 and SparseBitSet1.3 JARs have no embedded license/notice file. Include exact
  pinned source-release license copies in the DOCX module. CurvesAPI's actual source terms
  are Graph Builder's BSD-3-Clause license; its git-blob SHA1 matches the pinned tree.
- ONNX Runtime's JAR carries ThirdPartyNotices.txt, but no separate main-license entry.
  Supplement the adapter with its pinned MIT license. Keep the upstream native dependency
  and its bundled third-party notices intact; this does not replace those terms.
- The existing Microsoft TATR MIT notice matches its original pinned source byte for byte.
  The historical Unstructured reference license was retrieved and confirms Apache2.0;
  no upstream Python implementation is included in the library artifacts.

All supplemental source URLs, revisions and file hashes are in manifest.json. Exact license
texts are copied without edits. The upstream dependency JARs are not rewritten or shaded.
Jackson's embedded FastDoubleParser/Schubfach notices and other upstream notices remain with
their respective dependency JARs. Linking dependencies is distinct from bundling them into
an application distribution or a future shaded artifact, which needs its own review.

## Verification

Actual Java21/25 clean full release-artifacts builds pass899 tests/zero failures/errors/skips
and strict Javadocs. Each completed build is inspected before the next clean. All16 library
modules' binary/source/documentation archives contain the attribution guide and applicable
licenses byte for byte. Source bytes remain complete, documentation indexes/guides remain
present and the internal harnesses have no attached source/docs JARs.

The JDK-only benchmarks/tools/CheckReleaseArchives.java performs the same archive-contract
checks in CI after packaging. It also rejects corpus/model payload extensions and bounds
ZIP-entry reads by expected repository-file length. Both actual JVMs pass the real archive
check and strict tool Javadocs. A temporary one-module copy passes before removal of the
attribution guide; both JVMs reject it afterwards. Original artifacts are never mutated.
The guard supports this reactor's direct-child module layout; nested modules require an
explicit update. No Python runtime is introduced into the library or archive-check CI step.

tracked-binaries.json inventories27 owned DOCX fixtures and3 owned synthetic ONNX test graphs.
Twenty-five DOCX hashes match existing manifests; the two initial fixtures have recorded
synthetic provenance without historical hashes, so this audit records their current hashes
without rewriting those manifests. ONNX generation descriptions remain in test resources.
No original external PDF/image/font/model-weight binaries are tracked among these file types.
Their absence from library archives is independently checked. Hashes/provenance descriptions
are evidence records, not independent ownership adjudication.

## Remaining boundaries

Runtime metadata/notice inventory and known Maven-artifact gaps are addressed here. This is
not a vulnerability scan or a blanket legal clearance. Repository evidence/excerpts and
external corpora/model terms require a separate review before public source distribution;
fetch-only originals and no bundled weights remain the policy. Signing, release/version/tag,
Portal verification and actual publication have not occurred. Broad PDF quality gates remain
open. The source/artifact audit does not increase PDF extraction completeness.
