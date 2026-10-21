# Release automation

Release automation and macOS/Linux developer setup live in the separate private
[docweave-dev-tools repository](https://github.com/praveenpg/docweave-dev-tools).
Release maintainers can follow its README and docs/publishing.md for the consolidated script.
The private tools repository is optional: DocWeave contributors can build and test this
checkout directly with Java21+ and Maven3.9+ using `mvn verify`.

No credentials, private signing keys or machine-specific release outputs belong in this
repository. Public contributor build instructions remain in the [README](../README.md).

Starting with beta.6, write and review versioned [release notes](releases/README.md) and the
matching changelog before candidate preparation. After candidate verification, create an annotated
`vVERSION` tag at its exact source commit and a GitHub prerelease using those notes before
Central upload. Carry the notes through validation and publication, updating status after confirmation. The private publishing guide includes this required release step.
