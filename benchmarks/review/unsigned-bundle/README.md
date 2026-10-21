# Consolidated unsigned local review bundle

This bundle combines redacted source, source-only attribution, project Maven artifacts and
verification in one local archive. Version remains0.1.0-SNAPSHOT: no final beta version,
signing identity, namespace approval, repository visibility or publication was selected.
The [receipt](receipt.json) records the outer archive/manifest hashes and exact scope.
[Canonical coordinates and hashes](canonical-artifacts.json) identify the artifacts shipped.
The full source/archive/bundle payload stays local; only safe review evidence is tracked here.

## Contents and provenance

The consolidated source derives from baseline47843d8 and its independently identified
redacted-v1 projection. It includes the source-only attribution notice and scoped excerpt
review, adds narrow pointers to existing benchmark/publication docs, and updates its ledger.
It has1159files:23changed originals,1131unchanged originals and5added files. All Java,
Maven inputs, fixture bytes, licenses and packaged resources remain unchanged. Existing
review records retain their original parent bindings; their unchanged corpus manifests
support the narrow attribution check, not a new rights or PDF-quality decision.

The Maven-layout repository stages65project files across17coordinates:parentPOM and
16libraries, each with POM/binary/source/Javadoc artifacts. No compiled internal harnesses,
dependency JARs, signatures, repository metadata, Central-format sidecars, external original
documents or learned model weights are staged. The source TAR retains harness sources and
owned synthetic fixtures for reproducibility; synthetic ONNX graphs are not library payloads.
All canonical artifacts come from the completed Java21build;319binary classes have major65.
The separate Java25build is validation, not a second artifact set in the bundle.

The bundle contains81regular-file TAR members, with78payload files listed in its manifest
and79files covered by each checksum list (payload plus manifest). Lists exclude themselves;
the outer TAR hash is recorded outside the archive to avoid self-reference. Source-file
checksums apply after source TAR extraction. Per-JDK build checksum snapshots refer to the
completed build trees, whereas canonical-artifacts.json identifies repository/ paths.
TAR ordering/metadata is deterministic for the captured bytes; byte-identical rebuilds or
cross-JDK JAR identity are not claimed. The canonical captured artifacts are unchanged.

## Verification

Both actual Java21/25source builds passed899tests, zero failures/errors/skips and strict
Javadocs with release-artifacts+release-signing and gpg.skip=true. Archive guards check48
library archives;65file SHA256/SHA512 manifests pass after each build, before the next clean.
The source TAR's1159member bytes/modes are independently verified before build extraction.
Builds and consumers run outside the immutable source tree. Preserved snapshots and original
file hashes are checked again after consolidation and verification.

Independent PDF/Tesseract/ONNX consumer projects compile/run on BOTH JDKs against the SAME
canonical Java21-built project artifact hashes. Fresh per-consumer repositories stage project
files only from the bundle; a file-only cache supplies third-party dependencies/plugins.
The original cache's io.docweave group is replaced by the staged bundle in that mirror,
preventing old installed project artifacts from masking omissions. Runtime dependencies are
6/7/8JARs respectively. PDF checks ServiceLoader/native text; Tesseract checks its public
interface and typed missing-engine diagnostic; ONNX explicitly infers an owned constant graph
and checks managed lifecycle. No installed OCR engine, learned weights or general accuracy
comparison is selected. The receipt includes consumer dependencies/project hashes/log hashes.

Independent final TAR inspection verifies81member paths/bytes against the frozen payload,
65project artifacts,17POMs/48JARs and no signatures/internal artifacts. System SHA256/SHA512
checks pass79files each. A changed temporary parent-POM copy is rejected by both algorithms;
restoring it passes. Original staged artifacts are never modified by that control. Selected
private path/companion patterns have no matches in checked staged bytes/ZIP members; this is
a bounded privacy check, not absolute security or legal clearance.

No Maven deploy/upload, Git initialization, visibility/history change, tag or real key use
occurs. Earlier snapshots stay intact. Wider evidence-rights review, a final beta
version and signing identity, final bundle verification, namespace/Portal verification and
explicit publication instruction remain open. PDF remains85–90% by subjective feature
planning; broader scan/native/held-out/calibration/acceptance gates remain open.
