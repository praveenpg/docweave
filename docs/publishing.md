# Releases

## Current scoped release

On 28 October 2026, `io.docweave:docweave-core:0.1.0-beta.8` and its required
`io.docweave:docweave-parent:0.1.0-beta.8` POM were published to Maven Central.
Other beta.8 modules remain local/unpublished. Core alone installs no format parsers.
See the [beta.8 release notes](releases/0.1.0-beta.8.md) for exact scope and validation.

Published Maven coordinates are immutable. See [release notes](releases/README.md),
[PDF limitations](pdf-support.md) and the [changelog](../CHANGELOG.md).

Build and verify on Java21 before preparing a release. The standalone optional-adapter
[consumer checks](../benchmarks/review/adapter-consumers/README.md) build independent Tesseract
and ONNX projects against the exact reactor version, or against a release candidate's artifacts
with their recorded checksums. A full-library release requires them to pass. Use the Maven release
profiles to create source/Javadoc archives and signatures. Signing credentials
belong in the maintainer environment, never in this repository.

Starting with beta.6, releases require an annotated version tag at the verified
source commit and reviewed GitHub prerelease notes. Verify version, source, tag
and notes agree before Maven Central upload. Publication is a separate maintainer
action after validation. Never overwrite an existing version or tag.
