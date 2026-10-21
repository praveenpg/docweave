# Current snapshot public API consumers

All six independent consumer executions pass: PDF-only, optional Tesseract and optional ONNX,
each on actual Java21 and25. This checks packaged current snapshot JARs, not a beta release.
The [receipt](verification.json) binds current/copied source hashes, consumer JARs, Java21 class
major65, build/run logs and exact runtime artifact hashes. All six use identical installed
library bytes from production9fd30c7; no older snapshot JAR is accepted silently.

The PDF-only consumer has exactly six runtime JARs: core/pdf plus PDFBox, PDFBox IO, FontBox
and Commons Logging. Its owned minimal native PDF exercises discovery, all four strategy presets,
provider avoidance/forced recognition, actual-word provider table ownership, ALL_PAGES metadata,
the former three-argument paragraph-options constructor, PAGE_FRAGMENTS and unchanged untagged
assembler fallback. It uses explicitly supplied fixed OCR/geometry callbacks, not installed
engines, learned models or publisher labels. Existing library owned/corpus tests remain separate.

FAST retains native diagnostics. AUTO with a supplied recognizer retains identical native
elements but advertises its textless-page OCR capability, so its warning intentionally differs
from native-only discovery. The consumer checks this distinction rather than ignoring warnings.
Observed elapsed duration is not an extraction-equivalence field.

Tesseract has seven runtime JARs, no ONNX/other formats, checks SINGLE_COLUMN linkage and typed
failure of a deliberately absent engine. ONNX has eight JARs, no Tesseract/other formats, and
uses the existing owned constant-output graph to check native geometry, identities and managed
lifecycle. No installed OCR or learned model runs. Historical adapter manifests remain unchanged
and retain their original baseline/source/artifact identities.

## Reproduction

Install the current tested snapshot modules into an explicitly chosen populated Maven cache:

```sh
mvn -B -ntp -Dmaven.repo.local="$CACHE" \
  -pl docweave-pdf,docweave-ocr-tesseract,docweave-layout-onnx -am -DskipTests install
bash benchmarks/review/pdf-api-consumers/run.sh "$CACHE" "$NEW_OUTPUT"
```

The install step packages already verified production modules; it does not claim a new full
test run. Run the consumer runner separately under actual JAVA_HOME/PATH21 and25, with new
output directories. Linux tooling includes Bash, ripgrep, Maven, JDK21+, unzip, awk and GNU
realpath. Third-party plugins/dependencies resolve through a local file mirror, while only
required project snapshots are explicitly staged into each fresh consumer repository. No
tokens, keys, tag, release, version projection, Central deployment or publishing-quota use.

Run VerifyConsumers.java with repository root, installed cache, output21, output25 and NEW
receipt using Jackson on the validation classpath. It requires exact copied source identity,
dependency counts/bytes, Java21 class major and all six runtime success markers. Library APIs
and production algorithms are unchanged by these consumer-only additions.

An initial local install required one missing Maven plugin dependency. The first PDF consumer
attempt lacked explicitly staged snapshots; Maven's default release-only mirror would not
resolve them. A second consumer assertion incorrectly equated AUTO's configured-capability
warning to FAST's native-only warning. Both failures remain in local logs. The final runner
stages snapshots explicitly and verifies each correct diagnostic contract; final fresh runs
pass without weakening text, provider-call or runtime dependency checks.


A later [Java21-only source-tagged consumer](../pdf-tagged-api-consumer/README.md)
checks the newer class-span, caption, logical-cell-envelope and multiline-cell APIs
against current packaged core/PDF JARs. It has a separate six-artifact receipt and
owned source; this historical six-execution report remains unchanged.
