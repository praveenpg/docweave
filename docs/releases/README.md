# Release notes

Starting with **0.1.0-beta.6**, each release requires reviewed versioned notes
**before candidate preparation**, an annotated `vVERSION` tag at the verified source commit,
and a GitHub prerelease whose title/version/notes match that candidate before Central upload.
Keep the matching changelog entry, version, source revision and candidate scope consistent.
Update status only after the corresponding service confirms it; distinguish prepared, validated,
publishing and published. Published artifact bytes and coordinates remain immutable.

- [0.1.0-beta.8](0.1.0-beta.8.md): **parent/core published**; breaking `io.docweave` Java namespace.
  Other beta.8 modules, including PDF and optional adapters, remain local/unpublished.
- [0.1.0-beta.7](0.1.0-beta.7.md): local candidate preparation; cumulative PDF and JVM-scoring changes,
  unpublished. Signing, tag, prerelease and upload remain separate steps.
- [0.1.0-beta.6](0.1.0-beta.6.md): published; native/provider and tagged tables,
  tagged paragraphs, password support and explicit OCR configuration.
- [0.1.0-beta.5](0.1.0-beta.5.md): partially ruled native PDF tables with multilevel headers.

Use [the template](template.md) for the next release. Include changes, usage, upgrade impact,
verified runtimes, validation evidence and known limitations. Link relevant contracts and
reproducible evidence; distinguish development results from independently established accuracy.
List cumulative changes when intervening candidates were never published. Record availability
and propagation separately from successful upload/validation.

For each beta.6-or-later release, create the tag and GitHub prerelease using the
reviewed Markdown notes after candidate verification. Check the exact source commit and version;
refuse to move an existing tag or replace an existing release. Repository visibility is managed
separately from versioned releases.
Publish only after the release candidate and its notes have been reviewed.

## Branch and automation policy

- Development branches and PRs start from and target `dev`. `main` is the release branch.
- A release PR moves `dev` into `main`; every reactor POM, `.ci/release.json`, and versioned release notes must agree.
- `scope: core` publishes only the parent and JDK-only core after their scoped verification. `scope: full` requires the entire reactor and archive checks to pass and includes all deployable library modules. Test/validation harnesses remain excluded.
- Jenkins releases only a new, non-SNAPSHOT main version. Existing or partially published coordinates are never overwritten. Same-version complete scopes skip; partial publication fails for investigation.
- Jenkins owns release signing and Central credentials. GitHub Actions creates only public release metadata after an annotated tag; it has no Central/signing secrets.
- After an ambiguous upload/publish response, inspect the durable Jenkins receipt and Central deployment. Do not rerun a POST blindly. Validation/status recovery uses the original candidate bytes.

Beta.8 remains core-only. Publishing its other modules under the same already-used parent version is deliberately refused by automation; prepare a fresh full-library version and run the release gates at that source. The environment-dependent PNG assertion is corrected on dev, with its original fixture/hash preserved and a passing full Java 21 Jenkins build.
