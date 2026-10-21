# Release notes

Starting with **0.1.0-beta.6**, each release requires reviewed versioned notes
**before candidate preparation**, an annotated `vVERSION` tag at the verified source commit,
and a GitHub prerelease whose title/version/notes match that candidate before Central upload.
Keep the matching changelog entry, version, source revision and candidate scope consistent.
Update status only after the corresponding service confirms it; distinguish prepared, validated,
publishing and published. Published artifact bytes and coordinates remain immutable.

- [0.1.0-beta.7](0.1.0-beta.7.md): proposed notes only; cumulative PDF changes,
  candidate/version/tag/upload not prepared.
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
