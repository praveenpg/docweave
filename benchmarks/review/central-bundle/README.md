# Local Central-layout ZIP

Version `0.1.0-beta.1`; prepared2026-10-18 from the unchanged
[signed candidate](../signed-beta/README.md). The recorded `io.docweave` namespace status is Verified in Portal;
that authenticated account status was not independently accessed.

The ZIP contains exactly260 files in Maven repository layout:65 canonical Maven POM/JAR files,
65 original detached signatures, and130 MD5/SHA-1 checksum sidecars. It excludes review notes,
source TAR, public keys, internal harness artifacts, third-party JARs and repository metadata.
Every canonical/signature hash matches the signed candidate. All65 signatures verify again
with the bundled public key using gpgv. ZIP CRC, unique/exact names, member bytes and required
checksum values pass; repeated generation gives identical ZIP bytes for the captured artifacts.
This is not a reproducible-rebuild claim. Receipt records the outer identity.

A local Maven3.9.16 inheritance-assembler check confirms all16 library modules inherit parent
description/URL/developers/license/SCM metadata. An initial raw-child-only description assertion
was overly strict: descriptions are inherited, as verified directly with Maven's model API.
Offline `help:effective-pom` was unavailable because its plugin is not cached; no successful
help-plugin run is claimed. No POM or signed bytes were changed to address the checker.

No new Java test run was needed for unchanged canonical artifacts; prior actual21/25 runs
of899tests each and standalone consumers remain the build evidence. This local ZIP check is
not Portal validation, source/rights acceptance or release approval.

Read-only fingerprint lookups again returned HTTP404 at Ubuntu and OpenPGP keyservers on
2026-10-18. Public-key discoverability remains unresolved; local signatures still verify.
No key upload, account registration, DNS mutation, artifact upload, release, tag or visibility
change was performed.

Format checked against official [upload layout](https://central.sonatype.org/publish/publish-portal-upload/)
and [requirements](https://central.sonatype.org/publish/requirements/): checksums apply to POM/JAR
files; signatures do not themselves require checksums. All originals remain unchanged.
