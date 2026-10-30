# Releases

Published Maven coordinates are immutable. See [release notes](releases/README.md),
[PDF limitations](pdf-support.md) and the [changelog](../CHANGELOG.md).

Build and verify on Java21 before preparing a release. Use the Maven release
profiles to create source/Javadoc archives and signatures. Signing credentials
belong in the maintainer environment, never in this repository.

Starting with beta.6, releases require an annotated version tag at the verified
source commit and reviewed GitHub prerelease notes. Verify version, source, tag
and notes agree before Maven Central upload. Publication is a separate maintainer
action after validation. Never overwrite an existing version or tag.
