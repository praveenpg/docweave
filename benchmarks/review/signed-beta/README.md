# Local signed beta candidate

A separate `0.1.0-beta.1` review candidate derives from the [unsigned bundle](../unsigned-bundle/README.md).
The development reactor remains `0.1.0-SNAPSHOT`. Its 23 version-only changes cover reactor
POMs and independent consumer version references; production Java and fixtures are unchanged.
`version-projection.json` records exact before/after file hashes. Historical reports and
publication-projection documents retain their original dated scope; they are not new results.

Actual Java 21 and 25 each pass 899 tests with zero failures/errors/skips and strict Javadocs.
Both archive guards pass. The same captured Java21-built artifacts pass independent PDF,
Tesseract missing-engine and native ONNX owned-graph consumers on both JDKs. No new installed
OCR, downloaded model, held-out quality measurement or platform certification is claimed.

65 Maven files (parent and 16 libraries) have detached signatures from the configured
fingerprint `2AB2C6EC15BFAD082E407EB4858774B14CB559E9`. Every signature verifies, including
an independent public-key-only gpgv check. A modified temporary POM fails verification.
No secret key or passphrase was exported into this candidate or repository.

The local archive contains source, canonical Maven files, signatures, public key and review
records. Independent extraction passes both checksum lists and all 1159 source-file hashes.
Receipt records its identity; fixed archive metadata does not establish reproducible rebuilds.
Full archives and generated artifacts stay outside this repository.

This is a local signed review candidate, not a Central upload or publication approval.
Public-key server availability, namespace verification, Portal setup, source, attribution and scope
acceptance and publication remain open. No public visibility, tag, DNS or upload action occurred.
PDF remains approximately85–90% by subjective feature planning, not accuracy or release readiness.
