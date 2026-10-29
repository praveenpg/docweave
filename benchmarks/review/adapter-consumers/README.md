# Standalone optional-adapter consumers

Two independent Maven projects declare only one DocWeave dependency each; neither inherits
DocWeave's parent POM. Both compile with release21. The Bash/Linux runner creates a fresh
local repository per consumer, stages only parent/core/pdf and the selected adapter, and
resolves plugins/third-party dependencies through a file mirror of an existing Maven cache.
It copies each consumer into a fresh workspace, verifies runtime JAR names/counts,
compiles/runs each consumer and checks class major65. Fresh workspaces avoid a clean-plugin
dependency that may not exist in a file-only cache. Build failures print their retained log.
No remote repository is used by the consumer runner; prepare the cache explicitly first.

- Tesseract: seven runtime JARs, no ONNX Runtime or other format parsers. Exercises public
  interface construction, explicit SINGLE_COLUMN API linkage and typed OCR_FAILURE for a deliberately missing executable.
  This default path attempts a nonexistent temporary path, not a system-installed engine.
- ONNX: eight runtime JARs including ONNX Runtime, no Tesseract adapter or other format
  parsers. Explicitly loads the owned constant-output fixed-table graph, runs native CPU
  inference, checks3x2/5-anchor/merged-cell geometry, provenance, wrong-hash rejection,
  borrowed-raster integrity and managed-session closure. FLOAT coordinates use0.001pixel
  tolerance. The fixture path is an explicit program argument; it is not packaged in a
  library JAR or fetched from a network. No learned weights or OCR are involved.

Default CI runs these same checks on Java21/25 with no installed Tesseract requirement.
An optional run with both trusted resource environment variables recognizes the owned
raster text DOCWEAVE using the selected installed English model. This is an integration
smoke check on Linux, not recognition accuracy, platform certification or complete PDF
pipeline validation. Existing end-to-end PDF and corpus evidence remains separately scoped.

## Reproduce

From the DocWeave root, choose the actual JDK through JAVA_HOME/PATH and prepare the cache:

```sh
mvn -B -ntp -Dmaven.repo.local=/tmp/docweave-m2 \
  -pl docweave-ocr-tesseract,docweave-layout-onnx -am -DskipTests install
bash benchmarks/review/adapter-consumers/run.sh /tmp/docweave-m2 /tmp/new-consumer-check
```

**Version and artifact identity:**
- **Version:** the runner reads the DocWeave version from this checkout's root `pom.xml` (the reactor version). It
  passes the same version to both consumer POMs as `-Ddocweave.version`; the POMs declare no default.
- **Install path:** every staged POM and JAR must be byte-identical to this checkout's freshly built files
  (`pom.xml` and `*/target/*.jar`). A stale or foreign installed artifact therefore fails before Maven runs.
- **Resolution check:** after the build, only the staged DocWeave artifacts of that version may be present in the
  consumer's repository.
- **Checksums:** `candidate-artifacts.sha256` in the output directory records the SHA-256 of every staged
  DocWeave file.

Jenkins library CI uses `--from-reactor` after its full verification. The runner then stages this checkout's
freshly built POMs and JARs into its own fresh repository, with no install into the agent's shared Maven cache:

```sh
bash benchmarks/review/adapter-consumers/run.sh --from-reactor ~/.m2/repository /tmp/new-reactor-check
```

To consume a release candidate's exact bytes instead of an install, pass its repository and checksum record (the
Jenkins full-library release does this after capturing the candidate):

```sh
bash benchmarks/review/adapter-consumers/run.sh --version VERSION \
  --docweave-repository CANDIDATE/bundle/repository --checksums CANDIDATE/canonical-artifacts.json \
  /tmp/docweave-m2 /tmp/new-candidate-check
```

These cases are refused with a clear message, before any build:
- a different `--version` without a candidate repository;
- a missing artifact of the selected version;
- a stale installed artifact;
- an artifact whose checksum differs from, or is missing in, `canonical-artifacts.json`;
- an existing output directory.

The output directory must not exist. Use a fresh path for every run. Tools required by the
runner: Bash, Maven, JDK21+, unzip, awk, find and GNU realpath. Outputs include build/run
logs, resolved dependency names and javap bytecode inspection. Both JDK consumer runs in
this checkpoint use the same Java25-built, Java21-target installed library artifacts.
The runner is not part of the Maven reactor or production library dependencies.

Only for explicitly selected installed resources, prefix the Bash command with:

```sh
DOCWEAVE_CONSUMER_TESSERACT=/trusted/path/to/tesseract \
DOCWEAVE_CONSUMER_TESSDATA=/trusted/path/to/tessdata \
bash benchmarks/review/adapter-consumers/run.sh /tmp/docweave-m2 /tmp/new-native-check
```

The executable may also need its platform's shared-library search path configured outside
this runner. No resource is downloaded or installed by the checks. The graph's owned origin
and generation details are in [fixture provenance](../../../docweave-layout-onnx/src/test/resources/onnx/README.md).

## Recorded validation

The manifest below is historical evidence at its pinned source/artifact identities; later
consumer source additions do not rewrite those measurements. Current snapshot consumer checks
separately record JAR identities and fresh outcomes for the expanded public API checks.

The manifest records final source hashes, artifact identities, exact resolved dependencies,
Java21/25 default and explicit-engine results and installed engine/model fingerprints.
Initial exact-floating-point assertion failure is superseded by the tolerance-based runs;
it was a smoke-check assertion error, not a production inference regression.
Both sources pass standalone strict Javadocs on each JDK. The prior899-test Java21/25
release-profile verification remains applicable to unchanged production code; this
checkpoint does not claim a new full-reactor test run. No signing, publication, visibility,
version, package, model-selection or extraction behavior changes occur here.

Initial CI39090255770 did not pass: Java21 dependency resolution was rate-limited (HTTP429),
and Java25 lacked its Maven-version-selected clean plugin in the file mirror. The runner
now packages a fresh source workspace without invoking clean; neither failure is counted as
successful consumer validation. Subsequent paired results are recorded in the manifest.
