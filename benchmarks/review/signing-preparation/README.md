# Local signing preparation and digest rehearsal

This checkpoint adds an explicit Maven GPG3.2.8 profile and extends the JDK-only archive
guard to produce SHA-256/SHA-512 manifests. It creates no key, valid signature, Central
bundle, release version, tag or upload. The development version remains0.1.0-SNAPSHOT.

The effective Maven model builder checks all19 projects under three selections (57 models
per JVM): default, release-artifacts, and release-artifacts+release-signing. Default and
attachment-only builds contain no signing plugin; the combined profiles sign the parent
and16 libraries, while two internal harnesses skip. The exact model-check source is retained
in manifest.json and uses the Maven distribution's own model-builder libraries. Invoke it
with `java -cp "$MAVEN_HOME/lib/*" CheckDocweaveSigning.java "$PWD"` after extracting that
source to a temporary file. It does not invoke GPG or access keyrings.

On actual Java21 and25, run:

```sh
mvn -B -ntp -Prelease-artifacts,release-signing -Dgpg.skip=true clean verify
java benchmarks/tools/CheckReleaseArchives.java . --checksums
sha256sum -c target/release-checksums.sha256
sha512sum -c target/release-checksums.sha512
```

Archive/digest checks must finish before the next clean build. Saved manifests are historical
snapshots, not verification of later rebuilt files. Their65 paths comprise48 library archives
and17 repository POMs; internal harnesses are excluded. Digests refer to repository POM bytes,
not transformed deployment POMs. Signatures and Central-format sidecars are not included.
These local checks do not validate a signing identity, public-key availability or a final bundle.

The archive tool's strict standalone Javadocs pass on both JDKs. Independent system digest
verification checks every recorded hash; a copied-artifact mutation control must fail without
changing the original build. Runtime extraction source/dependency declarations are unchanged.
Full test runs use no optional native-engine/model properties and add no PDF quality claims.
See [publishing guidance](../../../docs/publishing.md) for secret handling and remaining gates.
