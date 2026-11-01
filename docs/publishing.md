# Publication identity

## Development versions and internal snapshots

Before the next runtime change, advance the development branch to `0.1.0-beta.11-SNAPSHOT`. Changed code is never built under a published version such as
`0.1.0-beta.8`. A release sets its own version deliberately, in the release change itself. The Central automation
runs only from the public `main` branch and refuses a POM version that differs from `.ci/release.json`, so a
SNAPSHOT version cannot trigger a Central release.

Verified development snapshots may be published by the maintainers' internal CI to a private Maven repository.
Ordinary builds never contact it: nothing in these POMs names a repository, and no build needs network access to
one. To consume internal snapshots, opt in with your own `~/.m2/settings.xml`, using the URL and an account that the
operator gives you:

```xml
<settings>
  <servers>
    <server>
      <id>docweave-internal</id>
      <username>${env.DOCWEAVE_INTERNAL_USER}</username>
      <password>${env.DOCWEAVE_INTERNAL_PASSWORD}</password>
    </server>
  </servers>
  <profiles>
    <profile>
      <id>docweave-internal</id>
      <repositories>
        <repository>
          <id>docweave-internal</id>
          <url>https://REPOSITORY-HOST/artifactory/libs-snapshot</url>
          <releases><enabled>false</enabled></releases>
          <snapshots><enabled>true</enabled><updatePolicy>always</updatePolicy></snapshots>
        </repository>
      </repositories>
    </profile>
  </profiles>
</settings>
```

Then build with `-Pdocweave-internal`. The profile adds one repository, used only for SNAPSHOT versions. It is not a
`<mirror>`: other dependencies still come from Maven Central, so the internal repository never becomes a proxy for
everything.

## Core-only publication — 28 October 2026

`io.docweave:docweave-core:0.1.0-beta.8` and its required parent POM are published on Maven Central.
The exact verified public source is `3bc9adf9178e279aca2d341b5c744b367619f1a3`, tagged `v0.1.0-beta.8`
in the public repository. Central deployment `a1a3660f-7e30-4eea-b711-e3da6fe623f1` is PUBLISHED;
all five public artifacts match their signed candidate hashes and signatures, and a downloaded-core
Java 21 consumer passes. Other beta.8 modules remain local/unpublished. Historical full-library results
below are not a new full-release qualification. See [release notes](releases/0.1.0-beta.8.md).

The Maven groupId is `io.docweave`, based on the `docweave.io` domain.
Parent coordinates: `io.docweave:docweave-parent:0.1.0-beta.8`. Selective artifacts such as
`io.docweave:docweave-docx:0.1.0-beta.8` share that group/version. The all-formats convenience
artifact remains `io.docweave:docweave:0.1.0-beta.8`; see [module dependencies](modules.md).

Java packages and imports use `io.docweave` starting with local beta.8. Earlier betas use
`dev.docweave`; upgrading requires rebuilding consumers after the source/binary breaking
[package migration](package-migration.md). Maven groupIds and Java package names can differ.
The local candidate does not authorize remote publication.

The `io.docweave` namespace status was recorded as **Verified** in Central Portal on 2026-10-18.
Public-key retrieval succeeded, and the retrieved key verified all65 beta signatures.
The recorded deployment `546ca93a-841b-4faa-bbdb-99a2c9dcfacf` passed Portal validation
(18component entries) and entered **PUBLISHING**. The PDF beta POM is publicly retrievable;
full artifact availability and final Portal status have not yet been checked.

The project uses Apache License2.0 and lists Praveen Govindan as its developer. The root LICENSE
and POM declare the license; module resources retain third-party notices. The development
repository remains private pending the reviewed public-source export. Java packages remain
`io.docweave`. Historical benchmark fingerprints retain their original coordinates.
See [Central registration status](central-registration.md) and the
[local Central-layout bundle](../benchmarks/review/central-bundle/README.md).

The sections below retain earlier preparation stages and their scoped evidence; statements
about pending registration, signing or upload describe those dated stages, not current status.

## Local beta readiness review

The [PDF beta checklist](pdf-beta-checklist.md) separates proposed extraction scope from release
prerequisites. A [local POM/artifact inventory](../benchmarks/review/pdf-beta-readiness/README.md)
records missing license/URL/developer/SCM metadata and source/Javadoc attachment/signing/publishing
configuration. Internal integration/validation modules already declare deployment skip.
Sonatype requirements and Portal guidance were rechecked2026-10-17 and linked in the checklist.
No release version, project license or publication configuration was selected by that review.

## Selected project license and repository metadata

The project license is [Apache License2.0](../LICENSE). Separately identified third-party
material retains its own terms; selecting a project license is not a complete redistribution
review. The existing Microsoft TATR MIT notice remains in the ONNX module. External models,
traineddata and fetch-only source corpora are not covered by the project license.

The parent POM declares the GitHub project URL and HTTPS/SSH SCM connections. Inherited URLs
do not append child artifact paths to the repository URL. The development SCM tag remains HEAD.
All current JAR modules are direct children of the parent; an inherited resource entry copies
the root LICENSE to META-INF/LICENSE while retaining each module's src/main/resources.
A future nested module layout needs to revisit that relative resource path.

The version remains0.1.0-beta.8. Developer metadata lists
Praveen Govindan and existing GitHub identity praveenpg; no email address is added. Signing is explicit opt-in; remote publishing remains unconfigured.
The local attachment profile below supplies source/documentation archives.

## Local source and documentation archives

Use the explicit packaging profile from the repository root:

```sh
mvn -Prelease-artifacts clean verify
```

It attaches `-sources.jar` and `-javadoc.jar` to each library module at package; the parent
is a POM and internal harness modules retain deployment skip. Default builds retain strict
Javadoc verification. The resource-only all-formats artifact supplies a usage guide/license
instead of inventing Java API documentation; actual API documentation is in its dependencies.
Source/binary JAR licenses use META-INF/LICENSE; generated Javadoc JARs include project and
applicable module notices under resources/META-INF. Existing module notices remain intact.

[Paired build/archive checks and a standalone PDF consumer](../benchmarks/review/release-artifacts/README.md)
pass on actual Java21 and25. The consumer uses only the PDF dependency, with no other format
parser or optional OCR/ONNX dependency. Its single native-text fixture is a smoke check, not
PDF quality or platform certification. CI exercises the profile after ordinary verification.

The profile attaches local snapshot artifacts; it creates no signing key, signature/checksum
bundle, deployment endpoint, tag or release. Source-publication evidence/rights review and final publication confirmation
remain separate; optional-adapter consumer smoke checks are recorded below; the scoped runtime attribution
review below records the dependency/archive inventory.

Plugin behavior follows the official [Maven Source documentation](https://maven.apache.org/plugins/maven-source-plugin/jar-no-fork-mojo.html)
and [Javadoc archive documentation](https://maven.apache.org/plugins/maven-javadoc-plugin/jar-mojo.html).

## Runtime attribution and archive checks

The [third-party review](../benchmarks/review/third-party-audit/README.md) inventories25 external
runtime artifacts across all formats and explicit adapters. It records effective POM license
metadata and embedded notices, not a blanket classification of every bundled subcomponent.
The updated [attribution guide](../THIRD_PARTY_NOTICES.md) is included alongside the project
license in binary/source/documentation archives. DOCX includes exact supplemental CurvesAPI
and SparseBitSet licenses; the ONNX adapter includes its runtime's MIT license and retains
the existing Table Transformer notice. Upstream dependency JARs are kept intact.

After packaging, run the JDK-only archive guard from the repository root:

```sh
java benchmarks/tools/CheckReleaseArchives.java
```

CI runs this guard on both JDKs. Source-publication evidence/excerpts, external model/corpus
rights and any future shaded/application distribution require their own review. This
Maven-artifact inventory neither signs a release nor closes the PDF recognition gates.

## Opt-in signing and local digest review

The `release-signing` profile pins Maven GPG Plugin3.2.8 with `bestPractices=true`.
Use it together with `release-artifacts`; signing alone does not attach source/docs archives.
It signs the parent POM, library POMs, main archives and attachments at verify. Internal
harnesses inherit their existing `maven.deploy.skip=true` as `gpg.skip=true`.
Ordinary builds and `release-artifacts` alone do not invoke GPG.

A configuration/build rehearsal disables signing explicitly and needs no keys:

```sh
mvn -Prelease-artifacts,release-signing -Dgpg.skip=true clean verify
java benchmarks/tools/CheckReleaseArchives.java . --checksums
sha256sum -c target/release-checksums.sha256
sha512sum -c target/release-checksums.sha512
```

The JDK-only guard validates archives before writing two local review manifests. They cover
all16 library binary/source/docs archives plus the parent and16 repository POMs (65 files),
exclude internal harnesses, and use streamed SHA-256/SHA-512 digests. Run verification from
the repository root against the same unchanged build. On macOS use `shasum -a 256 -c` and
`shasum -a 512 -c` instead. Rebuilds invalidate earlier manifests.
These are local review digests, not Central-format checksum sidecars or a deployment bundle.
They do not include detached signatures; deployed POM transformations and a final release
version require fresh checksums over the actual final bundle.

To sign a release with a configured signing identity:

```sh
mvn -Prelease-artifacts,release-signing -Dgpg.keyname=FULL_SIGNING_FINGERPRINT clean verify
```

The fingerprint is a placeholder, not a configured identity. Use a prepared GPG agent or
`MAVEN_GPG_PASSPHRASE` supplied outside source control; do not put passphrases in command-line
properties, POMs or Maven settings. No key is generated/imported and no signing credentials
are stored by this configuration. A rehearsal with `gpg.skip=true` is not evidence of valid
signatures. Actual signing, public-key availability, detached-signature verification and final
Central-format bundle checks remain pending. No deployment endpoint or publication workflow
is configured.

Configuration follows the official [Maven GPG sign goal](https://maven.apache.org/plugins/maven-gpg-plugin/sign-mojo.html).
[Central requirements](https://central.sonatype.org/publish/requirements/) still apply to the
actual release candidate; local SHA-256/SHA-512 manifests alone do not satisfy publication.
Guidance checked2026-10-17.

## Standalone optional-adapter consumers

[Independent Tesseract and ONNX consumer projects](../benchmarks/review/adapter-consumers/README.md)
compile and run on Java21/25 with isolated project coordinates and checked runtime classpaths.
CI uses the Tesseract missing-executable diagnostic and explicitly supplied owned synthetic
ONNX graph; no installed OCR engine or learned model download is required. Explicit local
installed-engine checks are separately recorded. These smoke checks establish public API,
dependency and native-lifecycle integration for the tested Linux setup, not broad platform
support, PDF accuracy or acceptance of a final signed release bundle.

The runner selects the DocWeave version from the reactor `pom.xml` and passes it to both consumer POMs. It stages
only that exact version and records the SHA-256 of every staged file. It refuses any of these:
- stale or foreign installed artifacts;
- missing artifacts;
- a different version without a candidate repository;
- checksum mismatches.

Three runs exercise it:
- the public and private GitHub workflows, after an install;
- Jenkins library CI full verification, with `--from-reactor`, so nothing is installed into the shared cache;
- the Jenkins full-library release qualification, against the captured candidate's `bundle/repository` with
  `canonical-artifacts.json` checksums.

A full-library release is not qualified unless these consumers pass (issue #54 corrected the earlier hard-coded
`0.1.0-SNAPSHOT` and the stale `dev.docweave` imports).

## Source repository and historical evidence

The [source-publication review](../benchmarks/review/source-publication/README.md) inventories
tracked files and local reachable history separately from Maven artifacts. Earlier private
handoffs remain in Git history, and some saved evidence retains machine-specific paths.
A private companion repository does not remove those earlier versions. Keep the current
repositories private while preparing an explicitly reviewed public-source snapshot or a
separately reviewed history plan. Existing rights/attribution and final release gates remain
open; this review makes no visibility change or blanket clearance claim.

## Local source snapshot candidate

A [pinned local source snapshot](../benchmarks/review/source-snapshot/README.md) exports the
committed tree without Git history, untracked output or local caches. Its receipt records
archive and full-manifest hashes, file/mode checks and paired-JDK build results from a
separate validation copy. The local candidate preserves evidence bytes and explicitly retains
privacy/attribution holds; it is not sanitized, signed or approved for public publication.
The full review manifest and source/archive copies stay local rather than being committed
as another source tree. Any later redacted public projection needs its own transformation
ledger, hashes and validation; historical evidence is not silently rewritten.

## Redacted source projection

A [separate local projection](../benchmarks/review/source-projection/README.md) replaces
recorded machine paths and private resume references, labels rewritten evidence as derived,
and records original/projected file hashes. The original snapshot and measurements remain
intact. All Java sources, fixture bytes, licenses and attribution remain unchanged. Fresh
archive-extraction build results are recorded separately from historical measurements.
This addresses the selected privacy findings; it does not approve source/excerpt rights,
choose a final beta, configure a real signing identity or authorize public publication.

## Selected external-excerpt attribution

The [scoped retained-excerpt review](../benchmarks/review/excerpt-attribution/README.md)
verifies13unchanged anchors against four pinned source DOCX files, current publisher OGL
statements/download links and the official licence conditions. A [source-only notice](../benchmarks/SOURCE_EXCERPT_ATTRIBUTION.md)
consolidates attribution. A local supplement is bound to the existing redacted candidate's
hashes without changing that archive. Incorporate these records into a newly hashed candidate
before selecting it for publication. Wider evidence rights and release approval
remain separate; existing pending human-review status and historical build records stay intact.

## Consolidated unsigned review bundle

The [local bundle receipt](../benchmarks/review/unsigned-bundle/README.md) binds consolidated
redacted source, retained-excerpt attribution and65project Maven files under17coordinates.
The canonical Java21-built artifacts pass independent PDF/OCR/ONNX consumer checks on both
Java21/25, while fresh source builds on each JDK pass899tests and strict Javadocs. This remains
an unsigned0.1.0-beta.8 review bundle, not a selected beta or Central upload bundle.
No final version, key use, namespace/Portal action, visibility change or publication occurs.
Source/coordinate/artifact manifests and pending attribution and release decisions are explicit.

A [locally signed beta review](../benchmarks/review/signed-beta/README.md) now records
`0.1.0-beta.1`, 65 verified signatures and Java21/25 validation. It is a separately versioned
successor of the unsigned candidate; the development reactor is now `0.1.0-beta.9-SNAPSHOT`.
It does not establish namespace ownership, keyserver availability or publication approval.

Follow the [Central registration checklist](central-registration.md) for public-key retrieval
and `io.docweave` DNS/Portal verification. The dated check could not retrieve the public key
from Ubuntu or OpenPGP keyservers; this does not invalidate local signature verification.

## Consolidated publishing script

The optional private release-tools repository provides the [single-script workflow](release-script.md) for a new release: committed source
snapshot, version projection, Java21/25 build/consumer checks, signing, ZIP preparation and
explicit Portal upload/status/publication commands. Local preparation is separate from remote
operations. The script does not repeat the already published beta or change repository visibility.
